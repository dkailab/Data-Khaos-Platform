package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.controller.WarehouseQueryController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class MaterializationQueryServiceTest {
    private static final String DATE = "2026-11-17";
    private static final String ASSET = "ads-asset";
    private static final String GENERATION = "v1";

    private boolean visible = true;
    private boolean restricted;
    private boolean qualityPassed = true;
    private boolean replaceDuringRead;
    private String marker = GENERATION;
    private int resultReads;
    private int markerReads;
    private String table = "ads";
    private Connection warehouse;

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
                Map<String, Object> evidence = new HashMap<>(Map.of(
                        "asset", Map.of("id", variables[0], "datasourceId", "ds", "databaseName", "warehouse", "tableName", table),
                        "publishedInstance", Map.of("id", "publication", "bizDate", DATE,
                                "writeMode", "REPLACE_BY_BIZ_DATE", "publishedAt", "2026-11-18T01:00:00",
                                "targetDatasourceId", "ds", "targetDatabaseName", "warehouse", "targetTable", table,
                                "materializationGeneration", GENERATION)));
                if (qualityPassed) evidence.put("qualityPass", Map.of("id", "quality-proof"));
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
                        markerReads++;
                        result.setRows(List.of(Map.of("instance_id", "publication", "partition_column", "stat_date", "generation", marker)));
                    } else {
                        resultReads++;
                        if (warehouse == null) {
                            assertTrue(sql.contains("`stat_date` = '" + DATE + "' LIMIT 100"));
                            result.setRows(List.of(Map.of("amount", 7)));
                        } else {
                            result.setRows(readWarehouse(sql));
                        }
                        if (replaceDuringRead) marker = "v2";
                    }
                    return R.ok(result);
                }
            }, json);

    @AfterEach
    void clearUser() throws SQLException {
        MetadataHolder.clear();
        if (warehouse != null) warehouse.close();
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

    @Test
    void statusEndpointReturnsOnlyVerifiedPublicationMetadata() throws Exception {
        use("alice");
        var mvc = MockMvcBuilders.standaloneSetup(new WarehouseQueryController(service)).build();
        var response = mvc.perform(get("/api/query/warehouse/v1/materializations/{assetId}/status", ASSET)
                        .param("bizDate", DATE))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        var body = json.readTree(response.getContentAsString());
        assertEquals(0, body.path("code").asInt(-1));
        assertEquals(json.valueToTree(Map.of("assetId", ASSET, "bizDate", DATE,
                "publicationId", "publication", "qualitySnapshotId", "quality-proof",
                "publishedAt", "2026-11-18T01:00:00")), body.path("data"));
        assertEquals(1, markerReads);
        assertEquals(0, resultReads);
    }

    @Test
    void statusRejectsUnauthorizedOrUnverifiedPublicationsBeforeReadingTarget() {
        assertEquals(401, assertThrows(BusinessException.class, () -> service.status(ASSET, DATE)).getCode());
        use("alice");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.status(ASSET, "2026-02-30")).getCode());
        visible = false;
        assertEquals(404, assertThrows(BusinessException.class, () -> service.status(ASSET, DATE)).getCode());
        visible = true;
        qualityPassed = false;
        assertEquals(404, assertThrows(BusinessException.class, () -> service.status(ASSET, DATE)).getCode());
        qualityPassed = true;
        restricted = true;
        assertEquals(403, assertThrows(BusinessException.class, () -> service.status(ASSET, DATE)).getCode());
        assertEquals(0, markerReads);
        assertEquals(0, resultReads);
    }

    @Test
    void statusRejectsMismatchedGenerationAndRechecksWithoutReadingRows() {
        use("alice");
        marker = "v2";
        assertThrows(BusinessException.class, () -> service.status(ASSET, DATE));
        marker = GENERATION;
        assertEquals("publication", service.status(ASSET, DATE).publicationId());
        assertEquals(2, markerReads);
        assertEquals(0, resultReads);
    }

    @Test
    void servesFixedContractWithRealSqlAndKeepsBusinessDatesSeparate() throws Exception {
        use("alice");
        createWarehouse();
        try (var stmt = warehouse.createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO warehouse.pr2_ads_region_gmv VALUES
                    ('2026-11-17', 'west', 300.50),
                    ('2026-11-17', 'east', 999999999999.99),
                    ('2026-11-18', 'west', 99.00)
                    """);
        }
        var mvc = MockMvcBuilders.standaloneSetup(new WarehouseQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/api/data/v1/order-region-gmv").param("bizDate", DATE))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        var body = json.readTree(response.getContentAsString());
        assertEquals(0, body.path("code").asInt(-1));
        assertEquals(json.valueToTree(Map.of("bizDate", DATE, "publicationId", "publication",
                "qualitySnapshotId", "quality-proof", "publishedAt", "2026-11-18T01:00:00",
                "truncated", false, "rows", List.of(Map.of("region", "east", "gmv", "999999999999.99"),
                        Map.of("region", "west", "gmv", "300.50")))), body.path("data"));
        assertEquals(2, markerReads);
        assertEquals(1, resultReads);
    }

    @Test
    void distinguishesEmptyReleasedPartitionFromMissingReleaseAndLimitsResults() throws Exception {
        use("alice");
        createWarehouse();
        var empty = service.orderRegionGmv(DATE);
        assertEquals("publication", empty.publicationId());
        assertTrue(empty.rows().isEmpty());
        assertFalse(empty.truncated());
        try (var stmt = warehouse.prepareStatement("INSERT INTO warehouse.pr2_ads_region_gmv VALUES (?, ?, 1)")) {
            for (int i = 0; i < 100; i++) {
                stmt.setString(1, DATE);
                stmt.setString(2, "r%03d".formatted(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        assertFalse(service.orderRegionGmv(DATE).truncated());
        try (var stmt = warehouse.createStatement()) {
            stmt.executeUpdate("INSERT INTO warehouse.pr2_ads_region_gmv VALUES ('2026-11-17', 'z', 1)");
        }
        var limited = service.orderRegionGmv(DATE);
        assertTrue(limited.truncated());
        assertEquals(100, limited.rows().size());
        assertEquals("r099", limited.rows().get(99).region());
        qualityPassed = false;
        assertEquals(404, assertThrows(BusinessException.class, () -> service.orderRegionGmv(DATE)).getCode());
        assertEquals(3, resultReads);
    }

    @Test
    void rejectsDeniedReadsAndDiscardsResultsIfReleaseChangesDuringQuery() throws Exception {
        assertEquals(401, assertThrows(BusinessException.class, () -> service.orderRegionGmv(DATE)).getCode());
        use("alice");
        createWarehouse();
        visible = false;
        assertEquals(404, assertThrows(BusinessException.class, () -> service.orderRegionGmv(DATE)).getCode());
        visible = true;
        restricted = true;
        assertEquals(403, assertThrows(BusinessException.class, () -> service.orderRegionGmv(DATE)).getCode());
        restricted = false;
        assertEquals(0, resultReads);
        replaceDuringRead = true;
        assertThrows(BusinessException.class, () -> service.orderRegionGmv(DATE));
        assertEquals(1, resultReads);
    }

    private void createWarehouse() throws SQLException {
        table = "pr2_ads_region_gmv";
        warehouse = DriverManager.getConnection("jdbc:h2:mem:;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (var stmt = warehouse.createStatement()) {
            stmt.execute("CREATE SCHEMA warehouse");
            stmt.execute("""
                    CREATE TABLE warehouse.pr2_ads_region_gmv (
                        stat_date VARCHAR(10) NOT NULL,
                        region VARCHAR(32) NOT NULL,
                        gmv DECIMAL(14,2) NOT NULL,
                        PRIMARY KEY (stat_date, region)
                    )
                    """);
        }
    }

    private List<Map<String, Object>> readWarehouse(String sql) {
        try (var stmt = warehouse.createStatement(); var result = stmt.executeQuery(sql)) {
            var rows = new ArrayList<Map<String, Object>>();
            while (result.next()) {
                rows.add(Map.of("region", result.getObject("region"), "gmv", result.getObject("gmv")));
            }
            return rows;
        } catch (SQLException e) {
            throw new AssertionError("执行 ADS 查询失败", e);
        }
    }

    private void use(String user) {
        MetadataHolder.set(AuthUser.builder().id(user).build());
    }
}
