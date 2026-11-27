package com.datakhaos.workflow.service;

import com.datakhaos.common.constant.CommonConstants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 元数据服务的最小客户端：只做「表名 → 资产 ID」和「写一条表级血缘」。
 *
 * <p>实时血缘不能阻塞作业提交：任何调用失败都降级为「未解析」，
 * 由 {@code flink_table_lineage} 保留名称，后续刷新可重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataLineageClient {

    static final String SERVICE_URL = "http://data-khaos-metadata/api/meta";

    private final @Qualifier("orchestrationRestTemplate") RestTemplate rest;

    /** 在元数据资产目录中按库表名查找资产；找不到或服务不可用返回 null。 */
    public String findAsset(String table, String database) {
        if (table == null || table.isBlank()) {
            return null;
        }
        try {
            ResponseEntity<JsonNode> response = rest.getForEntity(
                    SERVICE_URL + "/assets?q={q}&current=1&size=20", JsonNode.class, table);
            JsonNode body = response.getBody();
            if (body == null || body.path("code").asInt(-1) != 0) {
                return null;
            }
            for (JsonNode row : body.path("data").path("records")) {
                if (!table.equalsIgnoreCase(row.path("tableName").asText())) {
                    continue;
                }
                if (database != null && !database.isBlank()
                        && !database.equalsIgnoreCase(row.path("databaseName").asText())) {
                    continue;
                }
                String id = row.path("id").asText();
                if (!id.isBlank()) {
                    return id;
                }
            }
        } catch (RestClientException e) {
            log.debug("元数据资产解析失败 table={} database={}: {}", table, database, e.getMessage());
        }
        return null;
    }

    /** 写一条表级血缘；元数据侧按 source/target/type 去重。 */
    public void saveLineage(String sourceTableId, String targetTableId) {
        if (sourceTableId == null || targetTableId == null
                || sourceTableId.isBlank() || targetTableId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceTableId", sourceTableId);
        payload.put("targetTableId", targetTableId);
        payload.put("relationType", "FLINK");
        try {
            rest.exchange(SERVICE_URL + "/lineage", HttpMethod.POST,
                    new HttpEntity<>(payload, systemHeaders()), JsonNode.class);
        } catch (RestClientException e) {
            log.warn("写入实时血缘失败 {} -> {}: {}", sourceTableId, targetTableId, e.getMessage());
        }
    }

    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CommonConstants.HEADER_USER_ID, CommonConstants.DEFAULT_ADMIN_ID);
        headers.set(CommonConstants.HEADER_USERNAME, "system");
        headers.set(CommonConstants.HEADER_REAL_NAME, "实时血缘");
        headers.set(CommonConstants.HEADER_ROLES, CommonConstants.SUPER_ADMIN);
        return headers;
    }
}
