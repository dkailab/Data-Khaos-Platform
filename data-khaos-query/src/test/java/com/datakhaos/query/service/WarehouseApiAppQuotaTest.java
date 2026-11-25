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
import com.datakhaos.query.controller.WarehouseApiAppController;
import com.datakhaos.query.controller.WarehouseApiAuditController;
import com.datakhaos.query.controller.WarehouseApiController;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.datakhaos.query.mapper.WarehouseApiAppMapper;
import com.datakhaos.query.mapper.WarehouseApiCallAuditMapper;
import com.datakhaos.query.mapper.WarehouseApiCredentialMapper;
import com.datakhaos.query.mapper.WarehouseApiGrantMapper;
import com.datakhaos.query.mapper.WarehouseApiQuotaWindowMapper;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PRC 验收：应用身份、按 appId + apiVersionId 的配额，以及可按 requestId 追溯的调用审计。
 */
class WarehouseApiAppQuotaTest {
    private static final String APIS = "/api/query/warehouse/v1/projects/orders/apis";
    private static final String APPS = "/api/query/warehouse/v1/projects/orders/apps";
    private static final String AUDITS = "/api/query/warehouse/v1/projects/orders/audits";
    private static final String VERSION_PATH = "/region-sales/versions/1";
    private static final String DATE = "2026-11-17";
    private static final String RUNTIME_SUBJECT = "svc-orders";
    private static final String REQUEST_ID = "X-Request-Id";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger executions = new AtomicInteger();
    private Connection keepAlive;
    private SqlSessionTemplate session;
    private MockMvc mvc;
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
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));

        mvc = buildMvc();
        publishVersion();
    }

    /**
     * 每次调用新建一套服务实例，模拟独立部署的另一个实例——彼此只共享数据库。
     */
    private MockMvc buildMvc() {
        var versions = session.getMapper(WarehouseApiVersionMapper.class);
        var permissions = new PermissionApiClient(null) {
            @Override
            public UserPermissionDto getUserPermission(String user) {
                var permission = new UserPermissionDto();
                permission.setUserId(user);
                if (!Set.of("developer", "svc-orders", "svc-outsider", "svc-off").contains(user)) {
                    permission.setActive(false);
                    return permission;
                }
                permission.setActive(!"svc-off".equals(user));
                var membership = new ProjectGroupDto();
                membership.setId("svc-outsider".equals(user) ? "other" : "orders");
                if ("developer".equals(user)) membership.setCapabilityFlags(List.of("task:develop"));
                permission.setProjectGroups(List.of(membership));
                return permission;
            }
        };
        var materializations = new MaterializationQueryService(null, null, json) {
            @Override
            OrderRegionGmv orderRegionGmv(String bizDate, int limit) {
                executions.incrementAndGet();
                executionUser = MetadataHolder.getUserId();
                return new OrderRegionGmv(bizDate, "publication-1", "quality-1", "2026-11-18T01:00:00",
                        false, List.of());
            }
        };
        var apis = new WarehouseApiService(versions, permissions, materializations, new RealtimeAdsQueryService(null, null));
        var apps = new WarehouseApiAppService(session.getMapper(WarehouseApiAppMapper.class),
                session.getMapper(WarehouseApiGrantMapper.class), versions, apis, permissions);
        var credentials = new WarehouseApiCredentialService(session.getMapper(WarehouseApiCredentialMapper.class),
                versions, apis, apps,
                new WarehouseApiQuotaService(session.getMapper(WarehouseApiQuotaWindowMapper.class)),
                new WarehouseApiAuditService(session.getMapper(WarehouseApiCallAuditMapper.class)));
        return MockMvcBuilders.standaloneSetup(
                        new WarehouseApiController(apis, credentials),
                        new WarehouseApiAppController(apps, credentials),
                        new WarehouseApiAuditController(apis,
                                new WarehouseApiAuditService(session.getMapper(WarehouseApiCallAuditMapper.class))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new MetadataContextFilter())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    @Test
    void quotasAreIndependentPerAppAndRotationKeepsTheAppQuota() throws Exception {
        String a = register("app-settle");
        String b = register("app-report");
        grant(a, 2);
        grant(b, 5);
        String tokenA = issue(a);
        String tokenB = issue(b);
        resetWindow();

        assertSuccess(tokenA, null);
        assertSuccess(tokenA, null);
        assertEquals(429, status(tokenA, null), "第三个请求超出 app-settle 的 2 次/分钟");
        assertEquals(2, executions.get());

        // 另一个应用额度独立
        assertSuccess(tokenB, null);
        assertSuccess(tokenB, null);
        assertSuccess(tokenB, null);
        assertEquals(5, executions.get());

        // 轮换凭据不重置额度：同一 {appId, apiVersionId} 占同一份额度
        String rotated = issue(a);
        assertEquals(429, status(rotated, null));
        assertEquals(5, executions.get());

        // 另一个实例共享同一份额度
        assertEquals(429, buildMvc().perform(invoke(rotated, null)).andReturn().getResponse().getStatus());
        assertEquals(5, executions.get());
        assertSuccess(buildMvc().perform(invoke(tokenB, null)).andReturn().getResponse());
    }

    @Test
    void disabledAppAndRevokedGrantAreRejectedBeforeAnyQueryRuns() throws Exception {
        String a = register("app-settle");
        grant(a, 100);
        String token = issue(a);

        assertSuccess(token, null);
        assertEquals(RUNTIME_SUBJECT, executionUser, "应用凭据以运行主体身份执行");
        int afterFirst = executions.get();

        var disabled = call(post(APPS + "/" + a + "/status").param("revision", "1").param("enabled", "false"),
                "developer");
        assertEquals("DISABLED", disabled.path("data").path("status").asText());
        assertEquals(ResultCode.FORBIDDEN.getCode(), bodyCode(token));
        assertEquals(afterFirst, executions.get(), "停用应用后必须在查询前拒绝");

        call(post(APPS + "/" + a + "/status").param("revision", "2").param("enabled", "true"), "developer");
        assertSuccess(token, null);

        var grants = call(get(APPS + "/" + a + "/grants"), "developer").path("data").path("records");
        String grantId = grants.get(0).path("id").asText();
        call(post(APPS + "/" + a + "/grants/" + grantId + "/revoke"), "developer");
        int beforeRevoked = executions.get();
        assertEquals(ResultCode.FORBIDDEN.getCode(), bodyCode(token));
        assertEquals(beforeRevoked, executions.get(), "回收授权后必须在查询前拒绝");

        // 重新授权沿用同一行，可以继续调用
        grant(a, 100);
        assertSuccess(token, null);

        // 运行主体停用时同样在查询前拒绝
        execute("UPDATE warehouse_api_app SET runtime_subject = 'svc-off' WHERE id = '" + a + "'");
        int beforeOff = executions.get();
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), bodyCode(token));
        assertEquals(beforeOff, executions.get());
    }

    @Test
    void auditIsTraceableByRequestIdAndKeepsCredentialsOut() throws Exception {
        String a = register("app-settle");
        grant(a, 2);
        String token = issue(a);
        resetWindow();

        var first = call(invoke(token, "trace-0001"), null);
        assertEquals(200, first.getStatus());
        assertEquals("trace-0001", first.getHeader(REQUEST_ID));
        assertEquals("publication-1", first.path("data").path("publicationId").asText());
        assertSuccess(token, "trace-0002");
        var exceeded = call(invoke(token, "trace-0003"), null);
        assertEquals(429, exceeded.getStatus(), "超额必须以 HTTP 429 暴露给调用方");
        assertNotNull(exceeded.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals("trace-0003", exceeded.getHeader(REQUEST_ID));

        var audited = call(get(AUDITS).param("requestId", "trace-0001"), "developer").path("data");
        assertEquals(1, audited.path("total").asInt());
        var row = audited.path("records").get(0);
        assertEquals(a, row.path("appId").asText());
        assertEquals("region-sales", row.path("apiCode").asText());
        assertEquals(1, row.path("apiVersion").asInt());
        assertEquals(0, row.path("resultCode").asInt());
        assertEquals("publication-1", row.path("publicationId").asText());
        assertEquals("quality-1", row.path("qualitySnapshotId").asText());
        assertEquals(DATE, row.path("bizDate").asText());
        assertNotNull(row.get("calledAt"));
        // 审计只留元数据：凭据与原始业务结果都不进审计
        assertFalse(row.has("token"));
        assertFalse(row.has("tokenDigest"));
        assertFalse(row.has("rows"));

        // 超额调用同样留痕，结果码可查
        var rejected = call(get(AUDITS).param("requestId", "trace-0003"), "developer").path("data")
                .path("records").get(0);
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), rejected.path("resultCode").asInt());

        // 按项目与日期分页
        assertEquals(3, call(get(AUDITS).param("from", "2026-01-01").param("to", "2099-12-31"), "developer")
                .path("data").path("total").asInt());
        assertEquals(0, call(get(AUDITS).param("from", "2099-01-01"), "developer")
                .path("data").path("total").asInt());

        // 跨项目不可查
        assertEquals(ResultCode.NOT_FOUND.getCode(),
                call(get("/api/query/warehouse/v1/projects/other/audits"), "developer").path("code").asInt());
    }

    @Test
    void rejectsRuntimeSubjectsThatCannotRunInTheProject() throws Exception {
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), registerCode("app-bad", "svc-outsider"));
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), registerCode("app-bad", "admin"));
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), registerCode("app-bad", "svc-off"));
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), registerCode("app-bad", "nobody"));

        // 未获授权的版本不允许签发凭据
        String a = register("app-settle");
        assertEquals(ResultCode.FORBIDDEN.getCode(),
                call(post(APPS + "/" + a + "/credentials" + VERSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":24}"), "developer")
                        .path("code").asInt());

        grant(a, 10);
        assertNotNull(issue(a));
        var listed = call(get(APPS + "/" + a + "/credentials" + VERSION_PATH), "developer").path("data")
                .path("records").get(0);
        assertEquals(a, listed.path("appId").asText());
        assertFalse(listed.has("token"));
        assertFalse(listed.has("tokenDigest"));
    }

    // ==================== 辅助 ====================

    private void publishVersion() throws Exception {
        call(post(APIS + VERSION_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"区域成交额\",\"maxRows\":50}"), "developer");
        call(post(APIS + VERSION_PATH + "/publish").param("revision", "1"), "developer");
    }

    private String register(String name) throws Exception {
        return call(post(APPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"runtimeSubject\":\"" + RUNTIME_SUBJECT + "\"}"), "developer")
                .path("data").path("id").asText();
    }

    private int registerCode(String name, String runtimeSubject) throws Exception {
        return call(post(APPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"runtimeSubject\":\"" + runtimeSubject + "\"}"), "developer")
                .path("code").asInt();
    }

    private void grant(String appId, int quotaPerMinute) throws Exception {
        call(post(APPS + "/" + appId + "/grants").contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiCode\":\"region-sales\",\"version\":1,\"quotaPerMinute\":" + quotaPerMinute + "}"),
                "developer");
    }

    private String issue(String appId) throws Exception {
        var data = call(post(APPS + "/" + appId + "/credentials" + VERSION_PATH)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":24}"), "developer").path("data");
        return "Bearer " + data.path("token").asText();
    }

    private void execute(String sql) throws Exception {
        try (var statement = keepAlive.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void resetWindow() throws Exception {
        execute("DELETE FROM warehouse_api_quota_window");
    }

    private MockHttpServletRequestBuilder invoke(String token, String requestId) {
        var request = get("/api/data/v1/projects/orders/apis" + VERSION_PATH).param("bizDate", DATE)
                .header(HttpHeaders.AUTHORIZATION, token);
        if (requestId != null) request.header(REQUEST_ID, requestId);
        return request;
    }

    private int status(String token, String requestId) throws Exception {
        return mvc.perform(invoke(token, requestId)).andReturn().getResponse().getStatus();
    }

    private int bodyCode(String token) throws Exception {
        return call(invoke(token, null), null).path("code").asInt();
    }

    private void assertSuccess(String token, String requestId) throws Exception {
        assertSuccess(mvc.perform(invoke(token, requestId)).andReturn().getResponse());
    }

    private void assertSuccess(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus());
        assertEquals(0, json.readTree(response.getContentAsString()).path("code").asInt(-1),
                response.getContentAsString());
    }

    private record Body(JsonNode json, MockHttpServletResponse response) {
        public JsonNode path(String field) {
            return json.path(field);
        }

        public String getHeader(String name) {
            return response.getHeader(name);
        }

        public int getStatus() {
            return response.getStatus();
        }
    }

    private Body call(MockHttpServletRequestBuilder request, String user) throws Exception {
        if (user != null) request.header(HEADER_USER_ID, user);
        var response = mvc.perform(request).andReturn().getResponse();
        return new Body(json.readTree(response.getContentAsString()), response);
    }
}
