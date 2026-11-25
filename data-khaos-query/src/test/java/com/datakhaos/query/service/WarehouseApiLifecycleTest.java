package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.config.MybatisPlusConfig;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataContextFilter;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.permission.api.model.ProjectGroupDto;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.controller.WarehouseApiController;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
import com.datakhaos.query.mapper.WarehouseApiCredentialMapper;
import com.datakhaos.query.mapper.WarehouseApiAppMapper;
import com.datakhaos.query.mapper.WarehouseApiCallAuditMapper;
import com.datakhaos.query.mapper.WarehouseApiGrantMapper;
import com.datakhaos.query.mapper.WarehouseApiQuotaWindowMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class WarehouseApiLifecycleTest {
    private static final String ADMIN = "/api/query/warehouse/v1/projects/orders/apis";
    private static final String DATA = "/api/data/v1/projects/orders/apis";
    private static final String VERSION = "/region-sales/versions/";
    private static final String DATE = "2026-11-17";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger lastLimit = new AtomicInteger();
    private final RealtimeAdsQueryService realtime = new RealtimeAdsQueryService(null, null) {
        @Override
        Table resolve(String assetId) {
            return new Table("ds", "orders_serving", "ads_region_day");
        }

        @Override
        List<RegionSample> readRegions(Table table, LocalDate date, int limit) {
            return List.of(new RegionSample("east", "100.00", 2, "2026-11-17T00:05:12.345"));
        }

        @Override
        EventProgress readEventProgress(Table table, LocalDate date) {
            return new EventProgress("2026-11-17T00:05:13.000", 3, "2026-11-17T00:02:02.000");
        }
    };
    private Connection keepAlive;
    private MockMvc mvc;
    private boolean issuerActive = true;
    private String executionUser;

    @BeforeEach
    void setUp() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        keepAlive = database.getConnection();
        try (var statement = keepAlive.createStatement()) {
            for (String migration : List.of("migrate-warehouse-api-versions.sql",
                    "migrate-warehouse-api-credentials.sql", "migrate-warehouse-api-apps.sql",
                    "migrate-warehouse-api-realtime.sql")) {
                statement.execute(Files.readString(Path.of("..", "db", migration)).replace("USE data_khaos;", ""));
            }
        }
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("warehouse", new SpringManagedTransactionFactory(), database));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new StandardEnvironment()));
        configuration.addMapper(WarehouseApiVersionMapper.class);
        configuration.addMapper(WarehouseApiCredentialMapper.class);
        configuration.addMapper(WarehouseApiAppMapper.class);
        configuration.addMapper(WarehouseApiGrantMapper.class);
        configuration.addMapper(WarehouseApiQuotaWindowMapper.class);
        configuration.addMapper(WarehouseApiCallAuditMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var mapper = session.getMapper(WarehouseApiVersionMapper.class);
        var permissions = new PermissionApiClient(null) {
            @Override
            public UserPermissionDto getUserPermission(String user) {
                var permission = new UserPermissionDto();
                permission.setUserId(user);
                permission.setActive(issuerActive || !"developer".equals(user));
                var membership = new ProjectGroupDto();
                membership.setId("other-developer".equals(user) ? "other" : "orders");
                if (!"viewer".equals(user)) membership.setCapabilityFlags(List.of("task:develop"));
                permission.setProjectGroups(List.of(membership));
                return permission;
            }
        };
        var materializations = new MaterializationQueryService(null, null, json) {
            @Override
            OrderRegionGmv orderRegionGmv(String bizDate, int limit) {
                lastLimit.set(limit);
                executionUser = MetadataHolder.getUserId();
                return new OrderRegionGmv(bizDate, "publication", "quality", "2026-11-18T01:00:00", false, List.of());
            }
        };
        var apis = new WarehouseApiService(mapper, permissions, materializations, realtime);
        var appService = new WarehouseApiAppService(
                session.getMapper(WarehouseApiAppMapper.class),
                session.getMapper(WarehouseApiGrantMapper.class),
                mapper, apis, permissions);
        var credentials = new WarehouseApiCredentialService(session.getMapper(WarehouseApiCredentialMapper.class),
                mapper, apis, appService,
                new WarehouseApiQuotaService(session.getMapper(WarehouseApiQuotaWindowMapper.class)),
                new WarehouseApiAuditService(session.getMapper(WarehouseApiCallAuditMapper.class)));
        mvc = MockMvcBuilders.standaloneSetup(new WarehouseApiController(apis, credentials))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new MetadataContextFilter())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    @Test
    void publishesAnImmutableVersionThenOfflinesItWithoutAffectingOtherVersions() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        String v2 = ADMIN + VERSION + "2";
        assertEquals(1, call(draft(post(v1), 25), "developer", ResultCode.SUCCESS).path("revision").asInt());
        call(draft(post(v1), 30), "developer", ResultCode.DUPLICATE_KEY);
        call(invoke(1), "viewer", ResultCode.NOT_FOUND);
        assertEquals(0, lastLimit.get());

        assertEquals(2, call(draft(put(v1).param("revision", "1"), 30),
                "developer", ResultCode.SUCCESS).path("revision").asInt());
        call(post(v1 + "/publish").param("revision", "1"), "developer", ResultCode.BUSINESS_ERROR);
        var published = call(post(v1 + "/publish").param("revision", "2"), "developer", ResultCode.SUCCESS);
        assertEquals("PUBLISHED", published.path("status").asText());
        assertEquals("developer", published.path("publishedBy").asText());
        call(draft(put(v1).param("revision", "3"), 99), "developer", ResultCode.BUSINESS_ERROR);
        assertEquals(DATE, call(invoke(1), "viewer", ResultCode.SUCCESS).path("bizDate").asText());
        assertEquals(30, lastLimit.get());
        assertEquals("no-store", mvc.perform(invoke(1).header(HEADER_USER_ID, "viewer"))
                .andReturn().getResponse().getHeader("Cache-Control"));

        call(draft(post(v2), 50), "developer", ResultCode.SUCCESS);
        call(post(v2 + "/publish").param("revision", "1"), "developer", ResultCode.SUCCESS);
        var offlined = call(post(v1 + "/offline").param("revision", "3"), "developer", ResultCode.SUCCESS);
        assertEquals("developer", offlined.path("offlinedBy").asText());
        assertEquals("OFFLINE", offlined.path("status").asText());
        call(post(v1 + "/publish").param("revision", "4"), "developer", ResultCode.BUSINESS_ERROR);
        call(invoke(1), "viewer", ResultCode.NOT_FOUND);
        call(invoke(2), "viewer", ResultCode.SUCCESS);
        assertEquals(50, lastLimit.get());
        var list = call(get(ADMIN).param("size", "1"), "developer", ResultCode.SUCCESS);
        assertEquals(2, list.path("total").asInt());
        assertEquals(2, list.path("records").get(0).path("apiVersion").asInt());
    }

    @Test
    void enforcesProjectScopeAndDevelopPermissionBeforeChangingOrInvokingVersions() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        call(draft(post(v1), 30), null, ResultCode.UNAUTHORIZED);
        call(draft(post(v1), 30), "other-developer", ResultCode.NOT_FOUND);
        call(draft(post(v1), 30), "viewer", ResultCode.FORBIDDEN);
        call(draft(post(v1), 101), "developer", ResultCode.VALIDATE_FAILED);
        call(draft(post(v1), 30), "developer", ResultCode.SUCCESS);
        call(post(v1 + "/publish").param("revision", "1"), "viewer", ResultCode.FORBIDDEN);
        call(post(v1 + "/publish").param("revision", "1"), "developer", ResultCode.SUCCESS);
        call(invoke(1), "other-developer", ResultCode.NOT_FOUND);
        assertEquals(0, lastLimit.get());
        call(invoke(1), "viewer", ResultCode.SUCCESS);
    }

    @Test
    void machineCredentialsAreScopedRevocableAndFailClosedWhenIssuerIsDisabled() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        String management = v1 + "/credentials";
        call(draft(post(v1), 20), "developer", ResultCode.SUCCESS);
        call(issue(management), "developer", ResultCode.NOT_FOUND);
        call(post(v1 + "/publish").param("revision", "1"), "developer", ResultCode.SUCCESS);
        call(issue(management), "viewer", ResultCode.FORBIDDEN);
        var issued = call(issue(management), "developer", ResultCode.SUCCESS);
        String id = issued.path("credential").path("id").asText();
        String token = "Bearer " + issued.path("token").asText();

        var listed = call(get(management), "developer", ResultCode.SUCCESS).path("records").get(0);
        assertEquals(id, listed.path("id").asText());
        assertFalse(listed.has("token"));
        assertFalse(listed.has("tokenDigest"));

        call(machineInvoke(2, token), null, ResultCode.UNAUTHORIZED);
        call(get("/api/data/v1/projects/other/apis" + VERSION + "1").param("bizDate", DATE)
                .header(HttpHeaders.AUTHORIZATION, token), null, ResultCode.UNAUTHORIZED);
        call(machineInvoke(1, "Bearer dkwh_invalid"), null, ResultCode.UNAUTHORIZED);
        assertEquals(0, lastLimit.get());
        call(machineInvoke(1, token), null, ResultCode.SUCCESS);
        assertEquals("developer", executionUser);
        assertEquals(20, lastLimit.get());
        issuerActive = false;
        call(machineInvoke(1, token), null, ResultCode.UNAUTHORIZED);
        issuerActive = true;

        String revokePath = management + "/" + id + "/revoke";
        call(post(revokePath), "other-developer", ResultCode.NOT_FOUND);
        call(post(revokePath), "developer", ResultCode.SUCCESS);
        call(post(revokePath), "developer", ResultCode.SUCCESS);
        call(machineInvoke(1, token), null, ResultCode.UNAUTHORIZED);
        var issuedAgain = call(issue(management), "developer", ResultCode.SUCCESS);
        String secondToken = "Bearer " + issuedAgain.path("token").asText();
        call(machineInvoke(1, secondToken), null, ResultCode.SUCCESS);
        try (var statement = keepAlive.createStatement()) {
            statement.executeUpdate("UPDATE warehouse_api_credential SET expires_at = '2000-01-01 00:00:00'");
        }
        call(machineInvoke(1, secondToken), null, ResultCode.UNAUTHORIZED);

        String thirdToken = "Bearer " + call(issue(management), "developer", ResultCode.SUCCESS).path("token").asText();
        call(post(v1 + "/offline").param("revision", "2"), "developer", ResultCode.SUCCESS);
        call(machineInvoke(1, thirdToken), null, ResultCode.NOT_FOUND);
    }

    /** 实时契约走同一条版本生命周期，但返回的是观测时间与时效，而不是发布证据。 */
    @Test
    void servesRealtimeContractWithObservationTimeAndFreshness() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        var created = call(realtimeDraft(post(v1), 50, "mt_realtime_ads"), "developer", ResultCode.SUCCESS);
        assertEquals("REALTIME", created.path("dataMode").asText());
        assertEquals("Asia/Shanghai", created.path("observedTimezone").asText());
        assertEquals("CNY", created.path("amountUnit").asText());
        assertEquals(60, created.path("freshnessFreshSeconds").asInt());
        assertEquals(300, created.path("freshnessStaleSeconds").asInt());

        call(post(v1 + "/publish").param("revision", "1"), "developer", ResultCode.SUCCESS);
        var payload = call(invoke(1), "viewer", ResultCode.SUCCESS);
        assertEquals("REALTIME", payload.path("dataMode").asText());
        assertEquals("PRODUCED", payload.path("state").asText());
        // 观测时间为固定历史时刻，相对当前时间必然超过 STALE 阈值
        assertEquals("STALE", payload.path("freshness").asText());
        assertEquals("2026-11-17T00:05:12.345+08:00", payload.path("observedAt").asText());
        assertEquals("east", payload.path("regions").get(0).path("region").asText());
        assertEquals("100.00", payload.path("regions").get(0).path("gmv").asText());
        assertEquals(3, payload.path("progress").path("eventCount").asLong());

        var reconcile = call(get(ADMIN + VERSION + "1/reconcile").param("bizDate", DATE),
                "developer", ResultCode.SUCCESS);
        assertTrue(reconcile.path("matched").asBoolean());
        assertEquals("MATCHED", reconcile.path("regions").get(0).path("verdict").asText());
    }

    @Test
    void editingOnlyNameAndRowLimitPreservesRealtimeContract() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        call(post(v1).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"实时契约","maxRows":10,"dataMode":"REALTIME",
                 "realtimeAssetId":"mt_rt_ads","realtimeBatchAssetId":"mt_batch_ads",
                 "observedTimezone":"UTC","freshnessFreshSeconds":25,"freshnessStaleSeconds":90}
                """), "developer", ResultCode.SUCCESS);

        var edited = call(put(v1).param("revision", "1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed realtime","maxRows":30}
                        """), "developer", ResultCode.SUCCESS);
        assertEquals("Renamed realtime", edited.path("apiName").asText());
        assertEquals(30, edited.path("maxRows").asInt());
        assertEquals(2, edited.path("revision").asInt());
        assertEquals("REALTIME", edited.path("dataMode").asText());
        assertEquals("mt_rt_ads", edited.path("realtimeAssetId").asText());
        assertEquals("mt_batch_ads", edited.path("realtimeBatchAssetId").asText());
        assertEquals("UTC", edited.path("observedTimezone").asText());
        assertEquals("CNY", edited.path("amountUnit").asText());
        assertEquals(25, edited.path("freshnessFreshSeconds").asInt());
        assertEquals(90, edited.path("freshnessStaleSeconds").asInt());

        var batch = call(put(v1).param("revision", "2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"离线契约\",\"maxRows\":10,\"dataMode\":\"BATCH\"}"),
                "developer", ResultCode.SUCCESS);
        assertEquals("BATCH", batch.path("dataMode").asText());
        assertTrue(batch.path("realtimeAssetId").isNull());
    }

    @Test
    void rejectsRealtimeDraftWithoutAssetAndKeepsBatchAsDefault() throws Exception {
        String v1 = ADMIN + VERSION + "1";
        call(realtimeDraft(post(v1), 10, null), "developer", ResultCode.VALIDATE_FAILED);
        call(realtimeDraft(post(v1), 10, "bad asset!"), "developer", ResultCode.VALIDATE_FAILED);
        var batch = call(draft(post(v1), 10), "developer", ResultCode.SUCCESS);
        assertEquals("BATCH", batch.path("dataMode").asText());
        assertTrue(batch.path("realtimeAssetId").isNull());
    }

    private static MockHttpServletRequestBuilder realtimeDraft(MockHttpServletRequestBuilder request,
            int maxRows, String assetId) {
        String asset = assetId == null ? "" : ",\"realtimeAssetId\":\"" + assetId + "\"";
        return request.contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"实时区域成交额\",\"maxRows\":" + maxRows + ",\"dataMode\":\"REALTIME\""
                        + asset + ",\"realtimeBatchAssetId\":\"mt_batch_ads\"}");
    }

    private static MockHttpServletRequestBuilder issue(String management) {
        return post(management).contentType(MediaType.APPLICATION_JSON)
                .content("{\"appName\":\"orders-worker\",\"ttlHours\":24}");
    }

    private static MockHttpServletRequestBuilder machineInvoke(int version, String authorization) {
        return invoke(version).header(HttpHeaders.AUTHORIZATION, authorization);
    }

    private static MockHttpServletRequestBuilder draft(MockHttpServletRequestBuilder request, int maxRows) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"区域成交额\",\"maxRows\":" + maxRows + "}");
    }

    private static MockHttpServletRequestBuilder invoke(int version) {
        return get(DATA + VERSION + version).param("bizDate", DATE);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String user, ResultCode expected) throws Exception {
        if (user != null) request.header(HEADER_USER_ID, user);
        var response = mvc.perform(request).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        assertEquals(expected.getCode(), body.path("code").asInt(-1), body.toString());
        return body.path("data");
    }
}
