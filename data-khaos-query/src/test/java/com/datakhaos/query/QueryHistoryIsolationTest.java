package com.datakhaos.query;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.config.MybatisPlusConfig;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.model.R;
import com.datakhaos.common.security.MetadataContextFilter;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.config.QueryProperties;
import com.datakhaos.query.controller.QueryController;
import com.datakhaos.query.mapper.QueryHistoryMapper;
import com.datakhaos.query.service.QueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Statement;
import java.util.UUID;

import static com.datakhaos.common.constant.CommonConstants.HEADER_ROLES;
import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static com.datakhaos.common.constant.CommonConstants.SUPER_ADMIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class QueryHistoryIsolationTest {

    private static final String HISTORY = "/api/query/history";
    private static final String QUERY = """
            {"datasourceId":"sample","sql":"SELECT 1"}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private SqlSession session;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        MybatisConfiguration mapperConfig = new MybatisConfiguration();
        mapperConfig.setEnvironment(new Environment("test", new JdbcTransactionFactory(), database));
        mapperConfig.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new StandardEnvironment()));
        mapperConfig.addMapper(QueryHistoryMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(mapperConfig).openSession();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE query_history (
                      id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(32), datasource_id VARCHAR(32),
                      database_name VARCHAR(200), sql_text VARCHAR(1024), status INT, cost_ms BIGINT,
                      row_count INT, error_message VARCHAR(1000), create_time TIMESTAMP
                    )
                    """);
            statement.execute("""
                    INSERT INTO query_history (id, user_id, sql_text, create_time) VALUES
                      ('alice-1', 'alice', 'SELECT 1', TIMESTAMP '2026-11-18 10:00:00'),
                      ('bob-1', 'bob', 'SELECT 2', TIMESTAMP '2026-11-18 11:00:00'),
                      ('alice-2', 'alice', 'SELECT 3', TIMESTAMP '2026-11-18 12:00:00'),
                      ('legacy', NULL, 'SELECT 4', TIMESTAMP '2026-11-18 13:00:00')
                    """);
        }

        DatasourceApiClient datasource = new DatasourceApiClient(null) {
            @Override
            public R<QueryResult> executeRaw(String datasourceId, String sql) {
                return R.ok(new QueryResult());
            }
        };
        PermissionApiClient permissions = new PermissionApiClient(null) {
            @Override
            public UserPermissionDto getUserPermission(String userId) {
                return new UserPermissionDto();
            }
        };
        QueryService service = new QueryService(session.getMapper(QueryHistoryMapper.class),
                datasource, permissions, new QueryProperties());
        mvc = MockMvcBuilders.standaloneSetup(new QueryController(service))
                .addFilters(new MetadataContextFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void listUsesAuthenticatedOwnerBeforePagination() throws Exception {
        JsonNode alice = body(get(HISTORY).header(HEADER_USER_ID, "alice")
                .param("userId", "bob").param("size", "1"));
        assertEquals(0, alice.path("code").asInt(-1));
        assertEquals(2, alice.at("/data/total").asInt());
        assertEquals(1, alice.at("/data/records").size());
        assertEquals("alice-2", alice.at("/data/records/0/id").asText());

        JsonNode bob = body(get(HISTORY).header(HEADER_USER_ID, "bob"));
        assertEquals(1, bob.at("/data/total").asInt());
        assertEquals("bob-1", bob.at("/data/records/0/id").asText());
    }

    @Test
    void detailReturnsOnlyOwnedRecords() throws Exception {
        JsonNode owned = body(get(HISTORY + "/alice-1").header(HEADER_USER_ID, "alice"));
        assertEquals(0, owned.path("code").asInt(-1));
        assertEquals("SELECT 1", owned.at("/data/sqlText").asText());

        for (String id : new String[]{"bob-1", "legacy", "missing"}) {
            assertEquals(404, body(get(HISTORY + "/" + id)
                    .header(HEADER_USER_ID, "alice")).path("code").asInt());
        }
    }

    @Test
    void personalHistoryHasTheSameScopeForAdministrators() throws Exception {
        assertEquals(2, body(get(HISTORY).header(HEADER_USER_ID, "alice")
                .header(HEADER_ROLES, SUPER_ADMIN)).at("/data/total").asInt());
        assertEquals(404, body(get(HISTORY + "/bob-1").header(HEADER_USER_ID, "alice")
                .header(HEADER_ROLES, SUPER_ADMIN)).path("code").asInt());
    }

    @Test
    void missingIdentityRejectsHistoryReadsAndWrites() throws Exception {
        assertEquals(401, body(get(HISTORY).param("userId", "alice")).path("code").asInt());
        assertEquals(401, body(get(HISTORY + "/alice-1")).path("code").asInt());

        for (String path : new String[]{"/api/query/execute", "/api/query/export"}) {
            assertEquals(401, body(post(path).param("userId", "alice")
                    .contentType("application/json").content(QUERY)).path("code").asInt());
        }

        assertEquals(2, body(get(HISTORY).header(HEADER_USER_ID, "alice")).at("/data/total").asInt());
    }

    @Test
    void executionAndCsvExportWriteHistoryForAuthenticatedOwner() throws Exception {
        assertEquals(0, body(post("/api/query/execute").header(HEADER_USER_ID, "alice")
                .param("userId", "bob").contentType("application/json").content(QUERY))
                .path("code").asInt(-1));

        MockHttpServletResponse csv = mvc.perform(post("/api/query/export")
                .header(HEADER_USER_ID, "alice").param("userId", "bob")
                .contentType("application/json").content(QUERY)).andReturn().getResponse();
        assertEquals(200, csv.getStatus());
        assertTrue(csv.getHeader("Content-Disposition").contains("query_result.csv"));

        assertEquals(4, body(get(HISTORY).header(HEADER_USER_ID, "alice")).at("/data/total").asInt());
        assertEquals(1, body(get(HISTORY).header(HEADER_USER_ID, "bob")).at("/data/total").asInt());
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsByteArray());
    }
}
