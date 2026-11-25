package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.RealtimeReconcile;
import com.datakhaos.query.dto.RealtimeRegionGmv;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.datakhaos.common.constant.CommonConstants.HEADER_ROLES;
import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;

/**
 * 实时 ADS 读取与时效判定。
 *
 * <p>与 {@link MaterializationQueryService} 的分工：离线走「业务日分区发布 + 质量快照」证据链，
 * 实时没有分区发布，改用作业自己落库的两个信号判断结果是否可信：
 * <ol>
 *   <li><b>结果观测时间</b> {@code observed_at}：写入该行时由作业按契约时区落库，不是查询时取 now()，
 *       因此可以在库里直接 {@code SELECT MAX(observed_at)} 复核；</li>
 *   <li><b>作业推进信号</b> {@code <table>_events}：按业务日记录已消费的 CDC 事件，
 *       独立于「是否成交」，用来在结果为空时区分<b>确实没有成交</b>与<b>作业尚未推进到该业务日</b>。</li>
 * </ol>
 *
 * <p>契约把时区、金额单位与时效阈值固化在 API 版本上，响应原样回显，调用方无需猜测。
 */
@Service
public class RealtimeAdsQueryService {
    private static final String ASSET_URL = "http://data-khaos-metadata/api/meta/assets/{assetId}";
    private static final String RESTRICTED_URL =
            "http://data-khaos-permission/api/permission/policy/table-restricted?database={db}&table={table}";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern GMV_DECIMAL = Pattern.compile("-?[0-9]{1,14}\\.[0-9]{2}");
    private static final Pattern OBSERVED = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");
    private static final String EVENT_SUFFIX = "_events";
    private static final String CONTROL_TABLE = "dk_materialization_version";

    public static final String STATE_PRODUCED = "PRODUCED";
    public static final String STATE_EMPTY = "EMPTY";
    public static final String STATE_NOT_YET_PRODUCED = "NOT_YET_PRODUCED";
    public static final String FRESHNESS_FRESH = "FRESH";
    public static final String FRESHNESS_LAGGING = "LAGGING";
    public static final String FRESHNESS_STALE = "STALE";
    public static final String FRESHNESS_UNKNOWN = "UNKNOWN";

    private final RestTemplate http;
    private final DatasourceApiClient datasources;
    private Clock clock = Clock.systemUTC();

    public RealtimeAdsQueryService(@Qualifier("orchestrationRestTemplate") RestTemplate http,
                                   DatasourceApiClient datasources) {
        this.http = http;
        this.datasources = datasources;
    }

    /** 供测试注入固定时钟，避免时效判定依赖真实时间。 */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 实时契约的固定口径，来自 API 版本配置。
     *
     * @param assetId      实时 ADS 资产
     * @param batchAssetId 对账基线批 ADS 资产，可为空
     * @param timezone     observed_at 的解释时区
     * @param amountUnit   金额单位
     * @param freshSeconds lag 不超过该值视为 FRESH
     * @param staleSeconds lag 超过该值视为 STALE
     * @param maxRows      最大返回地区数
     */
    public record Contract(String assetId, String batchAssetId, String timezone, String amountUnit,
                           int freshSeconds, int staleSeconds, int maxRows) {}

    public RealtimeRegionGmv regionGmv(Contract contract, String requestedDate) {
        validate(contract);
        LocalDate date = businessDate(requestedDate);
        ZoneId zone = zone(contract);
        Table table = resolve(contract.assetId());
        int limit = contract.maxRows() + 1;
        List<RegionSample> rows = readRegions(table, date, limit);
        EventProgress progress = readEventProgress(table, date);

        boolean truncated = rows.size() > contract.maxRows();
        List<RegionSample> kept = truncated ? rows.subList(0, contract.maxRows()) : rows;
        return judge(contract, date, zone, kept, truncated, progress, clock);
    }

    /**
     * 逐地区对账：实时表与批表在同一业务日、同一契约口径下逐地区比对。
     * 两侧各自附带观测时间，便于判断「数值一致」与「哪一侧更新」。
     */
    public RealtimeReconcile reconcile(Contract contract, String requestedDate) {
        validate(contract);
        if (contract.batchAssetId() == null || contract.batchAssetId().isBlank()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "该 API 版本未配置对账基线批资产");
        }
        LocalDate date = businessDate(requestedDate);
        ZoneId zone = zone(contract);
        Table realtimeTable = resolve(contract.assetId());
        Table batchTable = resolve(contract.batchAssetId());

        List<RegionSample> realtimeRows = readRegions(realtimeTable, date, contract.maxRows() + 1);
        List<RegionSample> batchRows = readRegions(batchTable, date, contract.maxRows() + 1);
        EventProgress progress = readEventProgress(realtimeTable, date);

        boolean realtimeTruncated = realtimeRows.size() > contract.maxRows();
        boolean batchTruncated = batchRows.size() > contract.maxRows();
        if (realtimeTruncated) realtimeRows = realtimeRows.subList(0, contract.maxRows());
        if (batchTruncated) batchRows = batchRows.subList(0, contract.maxRows());

        RealtimeRegionGmv realtime = judge(contract, date, zone, realtimeRows, realtimeTruncated, progress, clock);
        RealtimeRegionGmv batch = judge(contract, date, zone, batchRows, batchTruncated, null, clock);

        Map<String, RegionSample> left = index(realtimeRows);
        Map<String, RegionSample> right = index(batchRows);
        TreeMap<String, RealtimeReconcile.RegionDiff> diffs = new TreeMap<>();
        for (var entry : left.entrySet()) {
            RegionSample other = right.get(entry.getKey());
            RegionSample mine = entry.getValue();
            if (other == null) {
                diffs.put(entry.getKey(), new RealtimeReconcile.RegionDiff(entry.getKey(), mine.gmv(), null,
                        mine.orderCount(), null, "ONLY_REALTIME"));
                continue;
            }
            boolean same = mine.gmv().equals(other.gmv()) && mine.orderCount() == other.orderCount();
            diffs.put(entry.getKey(), new RealtimeReconcile.RegionDiff(entry.getKey(), mine.gmv(), other.gmv(),
                    mine.orderCount(), other.orderCount(), same ? "MATCHED" : "MISMATCH"));
        }
        for (var entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                RegionSample other = entry.getValue();
                diffs.put(entry.getKey(), new RealtimeReconcile.RegionDiff(entry.getKey(), null, other.gmv(),
                        null, other.orderCount(), "ONLY_BATCH"));
            }
        }
        boolean matched = !realtime.truncated() && !batch.truncated()
                && !STATE_NOT_YET_PRODUCED.equals(realtime.state()) && !STATE_NOT_YET_PRODUCED.equals(batch.state())
                && diffs.values().stream().allMatch(diff -> "MATCHED".equals(diff.verdict()));
        return new RealtimeReconcile(date.toString(), matched, side(realtime), side(batch),
                new ArrayList<>(diffs.values()));
    }

    private static RealtimeReconcile.Side side(RealtimeRegionGmv payload) {
        return new RealtimeReconcile.Side(payload.state(), payload.observedAt(), payload.lagSeconds(),
                payload.freshness(), payload.rowCount(), payload.amountUnit(), payload.truncated());
    }

    private static Map<String, RegionSample> index(List<RegionSample> rows) {
        Map<String, RegionSample> index = new LinkedHashMap<>();
        for (RegionSample row : rows) index.put(row.region(), row);
        return index;
    }

    /**
     * 时效判定的纯逻辑，不触达外部依赖，便于覆盖空结果、时钟回拨与阈值边界。
     */
    static RealtimeRegionGmv judge(Contract contract, LocalDate date, ZoneId zone,
                                   List<RegionSample> rows, boolean truncated,
                                   EventProgress progress, Clock clock) {
        String state;
        if (!rows.isEmpty()) {
            state = STATE_PRODUCED;
        } else if (progress != null && progress.advancedAt() != null) {
            // 作业消费过该业务日的事件，只是没有成交单 → 空数据
            state = STATE_EMPTY;
        } else {
            // 没有任何推进信号 → 尚未产出，不能当作空数据
            state = STATE_NOT_YET_PRODUCED;
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(zone));
        String observedAt = null;
        Long lagSeconds = null;
        String freshness = FRESHNESS_UNKNOWN;
        if (state.equals(STATE_PRODUCED)) {
            LocalDateTime latest = null;
            for (RegionSample row : rows) {
                LocalDateTime candidate = parseTimestamp(row.observedAt());
                if (candidate != null && (latest == null || candidate.isAfter(latest))) latest = candidate;
            }
            if (latest != null) {
                observedAt = formatOffset(latest, zone);
                lagSeconds = lagSeconds(latest, now);
                freshness = classify(lagSeconds, contract);
            }
        }

        RealtimeRegionGmv.Progress signal = progress == null ? null
                : new RealtimeRegionGmv.Progress(
                        progress.advancedAt() == null ? null
                                : formatOffset(parseTimestamp(progress.advancedAt()), zone),
                        progress.advancedAt() == null ? null
                                : lagSeconds(parseTimestamp(progress.advancedAt()), now),
                        progress.eventCount(),
                        progress.maxEventTime() == null ? null
                                : formatOffset(parseTimestamp(progress.maxEventTime()), zone));

        List<RealtimeRegionGmv.RegionRow> regions = rows.stream()
                .map(row -> new RealtimeRegionGmv.RegionRow(row.region(), amount(row.gmv()), row.orderCount(),
                        row.observedAt() == null ? null : formatOffset(parseTimestamp(row.observedAt()), zone)))
                .toList();

        return new RealtimeRegionGmv(date.toString(), RealtimeRegionGmv.MODE, contract.timezone(),
                contract.amountUnit(), state, observedAt, lagSeconds, freshness,
                new RealtimeRegionGmv.FreshnessThresholds(contract.freshSeconds(), contract.staleSeconds()),
                signal, truncated, regions.size(), regions);
    }

    private static String classify(long lagSeconds, Contract contract) {
        if (lagSeconds <= contract.freshSeconds()) return FRESHNESS_FRESH;
        if (lagSeconds <= contract.staleSeconds()) return FRESHNESS_LAGGING;
        return FRESHNESS_STALE;
    }

    /** 时钟回拨或两侧时钟偏差会让 lag 为负，钳制到 0，避免被误判为「未来数据」。 */
    private static long lagSeconds(LocalDateTime observedAt, LocalDateTime now) {
        if (observedAt == null) return 0;
        long seconds = Duration.between(observedAt, now).getSeconds();
        return Math.max(seconds, 0);
    }

    private static String amount(String gmv) {
        if (gmv == null || !GMV_DECIMAL.matcher(gmv).matches()) {
            throw new BusinessException("实时 ADS 金额与契约不匹配（应为两位小数）");
        }
        return gmv;
    }

    private static String formatOffset(LocalDateTime value, ZoneId zone) {
        if (value == null) return null;
        return value.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replace(' ', 'T');
        if (!OBSERVED.matcher(normalized).matches()) {
            throw new BusinessException("实时 ADS 观测时间格式与契约不匹配");
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new BusinessException("实时 ADS 观测时间无法解析");
        }
    }

    private void validate(Contract contract) {
        if (contract.assetId() == null || !contract.assetId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "实时契约缺少有效的 ADS 资产");
        }
        if (contract.maxRows() < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "最大返回地区数无效");
        }
        if (contract.freshSeconds() < 0 || contract.staleSeconds() <= contract.freshSeconds()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "时效阈值无效（STALE 须大于 FRESH）");
        }
        zone(contract);
    }

    private static ZoneId zone(Contract contract) {
        try {
            return ZoneId.of(contract.timezone());
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "契约时区无效");
        }
    }

    private static LocalDate businessDate(String input) {
        if (input != null && input.length() == 10) {
            try {
                LocalDate date = LocalDate.parse(input);
                if (date.getYear() >= 1000) return date;
            } catch (DateTimeParseException ignored) {
                // 统一返回同一个校验错误
            }
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请选择 YYYY-MM-DD 格式的业务日期");
    }

    // ------------------------------------------------------------------ 外部读取（测试可覆盖）

    /** 解析资产到目标库表；与离线一致走 metadata 资产目录。 */
    Table resolve(String assetId) {
        String owner = MetadataHolder.getUserId();
        if (owner == null || owner.isBlank()) throw new BusinessException(ResultCode.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_USER_ID, owner);
        if (MetadataHolder.isSuperAdmin()) headers.set(HEADER_ROLES, "SUPER_ADMIN");

        R<Detail> body;
        try {
            body = http.exchange(ASSET_URL, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<R<Detail>>() {}, assetId).getBody();
        } catch (RestClientException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "元数据服务暂不可用");
        }
        if (body == null || body.getCode() != 0 || body.getData() == null
                || body.getData().table() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实时 ADS 资产不存在或不可见");
        }
        AssetTable asset = body.getData().table();
        if (!assetId.equals(asset.id()) || !identifier(asset.databaseName()) || !identifier(asset.tableName())
                || CONTROL_TABLE.equalsIgnoreCase(asset.tableName())
                || asset.datasourceId() == null || asset.datasourceId().isBlank()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实时 ADS 资产目标不合法");
        }
        if (Boolean.TRUE.equals(restricted(asset, headers))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "目标表存在行列策略，暂无实时契约查询");
        }
        return new Table(asset.datasourceId(), asset.databaseName(), asset.tableName());
    }

    private Boolean restricted(AssetTable asset, HttpHeaders headers) {
        try {
            R<Boolean> body = http.exchange(RESTRICTED_URL, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<R<Boolean>>() {},
                    asset.databaseName(), asset.tableName()).getBody();
            return body == null || body.getCode() != 0 ? Boolean.TRUE : body.getData();
        } catch (RestClientException e) {
            // 无法确认策略时按存在策略处理，宁可拒绝也不绕过
            return Boolean.TRUE;
        }
    }

    /** 读取结果行；观测时间统一按字符串取回，避免跨服务 JSON 边界的时区漂移。 */
    List<RegionSample> readRegions(Table table, LocalDate date, int limit) {
        String sql = """
                SELECT region,
                       TRIM(CAST(CAST(gmv_cents / 100 AS DECIMAL(18,2)) AS CHAR(64))) AS gmv,
                       paid_order_count,
                       DATE_FORMAT(observed_at, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS observed_at
                FROM %s.%s WHERE biz_date = '%s' ORDER BY region LIMIT %d
                """.formatted(quoted(table.database()), quoted(table.table()), date, limit);
        List<Map<String, Object>> rows = query(table.datasourceId(), sql).getRows();
        if (rows == null) return List.of();
        List<RegionSample> samples = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || !(row.get("region") instanceof String region)
                    || !(row.get("gmv") instanceof String gmv)) {
                throw new BusinessException("实时 ADS 数据与契约不匹配");
            }
            samples.add(new RegionSample(region, gmv, count(row.get("paid_order_count")),
                    row.get("observed_at") instanceof String value ? value : null));
        }
        return samples;
    }

    /** 读取作业推进信号：该业务日没有任何行即代表作业尚未推进到这里。 */
    EventProgress readEventProgress(Table table, LocalDate date) {
        String sql = """
                SELECT DATE_FORMAT(advanced_at, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS advanced_at,
                       event_count,
                       DATE_FORMAT(max_event_time, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS max_event_time
                FROM %s.%s WHERE biz_date = '%s' LIMIT 1
                """.formatted(quoted(table.database()), quoted(table.table() + EVENT_SUFFIX), date);
        List<Map<String, Object>> rows = query(table.datasourceId(), sql).getRows();
        if (rows == null || rows.isEmpty() || rows.get(0) == null) return null;
        Map<String, Object> row = rows.get(0);
        return new EventProgress(row.get("advanced_at") instanceof String value ? value : null,
                count(row.get("event_count")),
                row.get("max_event_time") instanceof String value ? value : null);
    }

    private QueryResult query(String datasourceId, String sql) {
        R<QueryResult> response = datasources.executeRaw(datasourceId, sql);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BusinessException("读取实时 ADS 失败，请检查目标库与推进表");
        }
        return response.getData();
    }

    private static long count(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ignored) {
                // 落到统一错误
            }
        }
        throw new BusinessException("实时 ADS 计数与契约不匹配");
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String quoted(String identifier) {
        return "`" + identifier + "`";
    }

    /** 目标表与其派生推进表：{@code <table>_events} 为作业级推进信号，{@code <table>_progress} 供运维交叉核对。 */
    record Table(String datasourceId, String database, String table) {}

    record RegionSample(String region, String gmv, long orderCount, String observedAt) {}

    record EventProgress(String advancedAt, long eventCount, String maxEventTime) {}

    private record Detail(AssetTable table, List<Object> relations) {}

    private record AssetTable(String id, String datasourceId, String databaseName, String tableName) {}
}
