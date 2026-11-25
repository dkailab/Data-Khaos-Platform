package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.MaterializationPreview;
import com.datakhaos.query.dto.MaterializationStatus;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.datakhaos.common.constant.CommonConstants.HEADER_ROLES;
import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;

@Service
public class MaterializationQueryService {
    private static final String EVIDENCE_URL =
            "http://data-khaos-metadata/api/meta/assets/{assetId}/evidence";
    private static final String RESTRICTED_URL =
            "http://data-khaos-permission/api/permission/policy/table-restricted?database={db}&table={table}";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final long CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final int MAX_CACHE_BYTES = 64 * 1024;
    private static final String ORDER_REGION_GMV_ASSET = "mt_pr2_ads_region_gmv";
    private static final String ORDER_REGION_GMV_TABLE = "pr2_ads_region_gmv";
    static final int ORDER_REGION_GMV_LIMIT = 100;
    private static final Pattern GMV_DECIMAL = Pattern.compile("-?[0-9]{1,12}\\.[0-9]{2}");

    private final RestTemplate http;
    private final DatasourceApiClient datasources;
    private final ObjectMapper json;
    private final Map<CacheKey, CacheValue> cache = new LinkedHashMap<>(16, 0.75f, true);

    public MaterializationQueryService(@Qualifier("orchestrationRestTemplate") RestTemplate http,
                                       DatasourceApiClient datasources, ObjectMapper json) {
        this.http = http;
        this.datasources = datasources;
        this.json = json;
    }

    public MaterializationPreview preview(String assetId, String requestedDate, boolean refresh) {
        VerifiedPublication verified = verifyPublication(assetId, requestedDate);
        Asset asset = verified.asset();
        LocalDate date = verified.date();
        Publication publication = verified.publication();
        Marker marker = verified.marker();
        CacheKey key = new CacheKey(MetadataHolder.getUserId(), asset, date, publication.id(), marker.generation());
        QueryResult result = refresh ? null : getCached(key);
        boolean hit = result != null;
        if (!hit) {
            String sql = "SELECT * FROM %s.%s WHERE %s = '%s' LIMIT 100".formatted(
                    quoted(asset.databaseName()), quoted(asset.tableName()), quoted(marker.partitionColumn()), date);
            result = query(asset.datasourceId(), sql);
            if (!marker.equals(readMarker(asset, publication, date))) {
                throw new BusinessException("物化版本正在更新，请刷新后重试");
            }
            cache(key, result);
        }
        return new MaterializationPreview(asset.tableName(), date.toString(), publication.id(),
                verified.quality().id(), publication.publishedAt(), hit, result);
    }

    public MaterializationStatus status(String assetId, String requestedDate) {
        VerifiedPublication verified = verifyPublication(assetId, requestedDate);
        Publication publication = verified.publication();
        return new MaterializationStatus(verified.asset().id(), verified.date().toString(),
                publication.id(), verified.quality().id(), publication.publishedAt());
    }

    public OrderRegionGmv orderRegionGmv(String requestedDate) {
        return orderRegionGmv(requestedDate, ORDER_REGION_GMV_LIMIT);
    }

    OrderRegionGmv orderRegionGmv(String requestedDate, int limit) {
        VerifiedPublication verified = verifyPublication(ORDER_REGION_GMV_ASSET, requestedDate);
        Asset asset = verified.asset();
        Marker marker = verified.marker();
        LocalDate date = verified.date();
        Publication publication = verified.publication();
        if (!ORDER_REGION_GMV_TABLE.equals(asset.tableName()) || !"stat_date".equals(marker.partitionColumn())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发布目标与地区成交额契约不匹配");
        }
        // Convert the DECIMAL at the source to retain its scale across the datasource JSON boundary.
        String sql = """
                SELECT region, TRIM(CAST(gmv AS CHAR(64))) AS gmv FROM %s.%s
                WHERE stat_date = '%s' ORDER BY region LIMIT %d
                """.formatted(quoted(asset.databaseName()), quoted(asset.tableName()), date, limit + 1);
        List<Map<String, Object>> rows = query(asset.datasourceId(), sql).getRows();
        if (!marker.equals(readMarker(asset, publication, date))) {
            throw new BusinessException("物化版本正在更新，请刷新后重试");
        }
        if (rows == null) {
            throw new BusinessException("地区成交额查询未返回有效结果");
        }
        List<OrderRegionGmv.RegionAmount> amounts = rows.stream().limit(limit)
                .map(MaterializationQueryService::regionAmount)
                .toList();
        return new OrderRegionGmv(date.toString(), publication.id(), verified.quality().id(),
                publication.publishedAt(), rows.size() > limit, amounts);
    }

    private static OrderRegionGmv.RegionAmount regionAmount(Map<String, Object> row) {
        if (row == null || !(row.get("region") instanceof String region)
                || !(row.get("gmv") instanceof String gmv) || !GMV_DECIMAL.matcher(gmv).matches()) {
            throw new BusinessException("地区成交额数据与 API 契约不匹配");
        }
        return new OrderRegionGmv.RegionAmount(region, gmv);
    }

    private VerifiedPublication verifyPublication(String assetId, String requestedDate) {
        String owner = MetadataHolder.getUserId();
        if (owner == null || owner.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (assetId == null || !assetId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "资产 ID 无效");
        }
        LocalDate date = businessDate(requestedDate);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_USER_ID, owner);
        if (MetadataHolder.isSuperAdmin()) {
            headers.set(HEADER_ROLES, "SUPER_ADMIN");
        }
        Evidence evidence = lookup(EVIDENCE_URL + "?bizDate={date}", headers,
                new ParameterizedTypeReference<R<Evidence>>() {}, assetId, date.toString());
        Asset asset = evidence.asset();
        Publication publication = evidence.publishedInstance();
        if (asset == null || !assetId.equals(asset.id()) || publication == null
                || !date.toString().equals(publication.bizDate()) || publication.publishedAt() == null
                || publication.materializationGeneration() == null
                || !"REPLACE_BY_BIZ_DATE".equalsIgnoreCase(publication.writeMode())
                || evidence.qualityPass() == null || evidence.qualityPass().id() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该业务日没有可查询的质量通过发布");
        }
        if (!identifier(asset.databaseName()) || !identifier(asset.tableName())
                || "dk_materialization_version".equalsIgnoreCase(asset.tableName())
                || !Objects.equals(asset.datasourceId(), publication.targetDatasourceId())
                || !asset.databaseName().equals(publication.targetDatabaseName())
                || !asset.tableName().equals(publication.targetTable())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发布目标与资产不匹配");
        }
        if (Boolean.TRUE.equals(lookup(RESTRICTED_URL, headers, new ParameterizedTypeReference<R<Boolean>>() {},
                asset.databaseName(), asset.tableName()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "目标表存在行列策略，暂无受管物化查询");
        }
        if (!"MYSQL".equals(datasources.datasourceType(asset.datasourceId()))) {
            throw new BusinessException("受管物化查询仅支持 MySQL");
        }

        return new VerifiedPublication(asset, date, publication, evidence.qualityPass(),
                readMarker(asset, publication, date));
    }

    private Marker readMarker(Asset asset, Publication publication, LocalDate date) {
        String sql = """
                SELECT instance_id, partition_column, generation FROM %s.dk_materialization_version
                WHERE target_table = '%s' AND biz_date = '%s' LIMIT 1
                """.formatted(quoted(asset.databaseName()), asset.tableName(), date);
        QueryResult result = query(asset.datasourceId(), sql);
        Map<String, Object> row = result.getRows() == null || result.getRows().isEmpty()
                ? Map.of() : result.getRows().get(0);
        if (publication.id() == null || !publication.id().equals(row.get("instance_id"))
                || !(row.get("partition_column") instanceof String column) || !identifier(column)
                || !(row.get("generation") instanceof String generation) || !generation.equals(publication.materializationGeneration())) {
            throw new BusinessException("物化版本尚未就绪，请核对最近的分区发布");
        }
        return new Marker(column, generation);
    }

    private QueryResult query(String datasourceId, String sql) {
        R<QueryResult> response = datasources.executeRaw(datasourceId, sql);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BusinessException("读取受管物化结果失败，请检查目标库和版本表");
        }
        return response.getData();
    }

    private <T> T lookup(String url, HttpHeaders headers, ParameterizedTypeReference<R<T>> type, Object... params) {
        R<T> body;
        try {
            body = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), type, params).getBody();
        } catch (RestClientException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "发布或权限服务暂不可用");
        }
        if (body != null && (body.getCode() == 404 || body.getCode() == 403)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资产发布不可见");
        }
        if (body == null || body.getCode() != 0 || body.getData() == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "暂时无法确认发布或权限");
        }
        return body.getData();
    }

    private synchronized QueryResult getCached(CacheKey key) {
        CacheValue entry = cache.get(key);
        if (entry == null) return null;
        if (System.nanoTime() - entry.createdAt() < CACHE_TTL_NANOS) return entry.result();
        cache.remove(key);
        return null;
    }

    private void cache(CacheKey key, QueryResult result) {
        try {
            if (json.writeValueAsBytes(result).length > MAX_CACHE_BYTES) return;
        } catch (JsonProcessingException e) {
            return;
        }
        synchronized (this) {
            cache.put(key, new CacheValue(result, System.nanoTime()));
            if (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
        }
    }

    private static LocalDate businessDate(String input) {
        if (input != null && input.length() == 10) {
            try {
                LocalDate date = LocalDate.parse(input);
                if (date.getYear() >= 1000) return date;
            } catch (DateTimeParseException ignored) {
                // Use the same validation response for all invalid input.
            }
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请选择 YYYY-MM-DD 格式的业务日期");
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String quoted(String identifier) {
        return "`" + identifier + "`";
    }

    private record Evidence(Asset asset, Publication publishedInstance, Quality qualityPass) {}
    private record Asset(String id, String datasourceId, String databaseName, String tableName) {}
    private record Publication(String id, String bizDate, String writeMode, String targetDatasourceId, String targetDatabaseName,
                               String targetTable, String publishedAt, String materializationGeneration) {}
    private record Quality(String id) {}
    private record Marker(String partitionColumn, String generation) {}
    private record VerifiedPublication(Asset asset, LocalDate date, Publication publication, Quality quality, Marker marker) {}
    private record CacheKey(String userId, Asset asset, LocalDate date, String publicationId, String generation) {}
    private record CacheValue(QueryResult result, long createdAt) {}
}
