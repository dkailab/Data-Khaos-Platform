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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            for (String migration : List.of("migrate-warehouse-api-versions.sql", "migrate-warehouse-api-credentials.sql")) {
                statement.execute(Files.readString(Path.of("..", "db", migration)).replace("USE data_khaos;", ""));
            }
        }
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("warehouse", new SpringManagedTransactionFactory(), database));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new StandardEnvironment()));
        configuration.addMapper(WarehouseApiVersionMapper.class);
        configuration.addMapper(WarehouseApiCredentialMapper.class);
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
        var apis = new WarehouseApiService(mapper, permissions, materializations);
        var credentials = new WarehouseApiCredentialService(session.getMapper(WarehouseApiCredentialMapper.class),
                mapper, apis, materializations);
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
