package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;

class MaterializationQueryServiceTest {
    private static final String DATE = "2026-11-17";
    private static final String ASSET = "ads-asset";
    private static final String GENERATION = "v1";

    private boolean visible = true;
    private boolean restricted;
    private boolean replaceDuringRead;
    private String marker = GENERATION;
    private int resultReads;

    private final ObjectMapper json = new ObjectMapper();
    private final RestTemplate http = new RestTemplate() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> request,
                                              ParameterizedTypeReference<T> responseType, Object... variables) {
            assertEquals(MetadataHolder.getUserId(), request.getHeaders().getFirst(HEADER_USER_ID));
            R<?> response;
            if (url.contains("table-restricted")) {
                response = R.ok(restricted);
            } else if (visible) {
                Object evidence = Map.of(
                        "asset", Map.of("id", ASSET, "datasourceId", "ds", "databaseName", "warehouse", "tableName", "ads"),
                        "publishedInstance", Map.of("id", "publication", "bizDate", DATE,
                                "writeMode", "REPLACE_BY_BIZ_DATE", "publishedAt", "2026-11-18T01:00:00",
                                "targetDatasourceId", "ds", "targetDatabaseName", "warehouse", "targetTable", "ads",
                                "materializationGeneration", GENERATION),
                        "qualityPass", Map.of("id", "quality-proof"));
                response = R.ok(json.convertValue(evidence, json.constructType(responseType.getType()).containedType(0)));
            } else {
                response = R.fail(ResultCode.NOT_FOUND.getCode(), "不可见");
            }
            return (ResponseEntity<T>) ResponseEntity.ok(response);
        }
    };

    private final MaterializationQueryService service = new MaterializationQueryService(http,
            new DatasourceApiClient(http) {
                @Override
                public String datasourceType(String dsId) {
                    return "MYSQL";
                }

                @Override
                public R<QueryResult> executeRaw(String dsId, String sql) {
                    assertEquals("ds", dsId);
                    QueryResult result = new QueryResult();
                    if (sql.contains("dk_materialization_version")) {
                        result.setRows(List.of(Map.of("instance_id", "publication", "partition_column", "stat_date", "generation", marker)));
                    } else {
                        assertTrue(sql.contains("`stat_date` = '" + DATE + "' LIMIT 100"));
                        resultReads++;
                        result.setRows(List.of(Map.of("amount", 7)));
                        if (replaceDuringRead) marker = "v2";
                    }
                    return R.ok(result);
                }
            }, json);

    @AfterEach
    void clearUser() {
        MetadataHolder.clear();
    }

    @Test
    void scopesCacheByUserAndChecksLiveAccessOnEveryHit() {
        use("alice");
        assertFalse(service.preview(ASSET, DATE, false).cacheHit());
        assertTrue(service.preview(ASSET, DATE, false).cacheHit());
        use("bob");
        assertFalse(service.preview(ASSET, DATE, false).cacheHit());
        use("alice");
        restricted = true;
        assertEquals(403, assertThrows(BusinessException.class, () -> service.preview(ASSET, DATE, false)).getCode());
        restricted = false;
        visible = false;
        assertEquals(404, assertThrows(BusinessException.class, () -> service.preview(ASSET, DATE, false)).getCode());
        assertEquals(2, resultReads);
    }

    @Test
    void refusesToServeOrCacheDataWhenPublicationChangesDuringReading() {
        use("alice");
        replaceDuringRead = true;
        assertThrows(BusinessException.class, () -> service.preview(ASSET, DATE, false));
        marker = GENERATION;
        replaceDuringRead = false;
        assertFalse(service.preview(ASSET, DATE, false).cacheHit());
        assertEquals(2, resultReads);
    }

    private void use(String user) {
        MetadataHolder.set(AuthUser.builder().id(user).build());
    }
}
