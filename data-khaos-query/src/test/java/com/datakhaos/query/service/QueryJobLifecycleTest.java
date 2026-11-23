package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.common.config.MybatisPlusConfig;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.model.R;
import com.datakhaos.common.security.MetadataContextFilter;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.query.config.QueryProperties;
import com.datakhaos.query.controller.QueryJobController;
import com.datakhaos.query.entity.QueryJob;
import com.datakhaos.query.entity.QueryJobStatus;
import com.datakhaos.query.mapper.QueryHistoryMapper;
import com.datakhaos.query.mapper.QueryJobMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class QueryJobLifecycleTest {
    private static final String JOBS = "/api/query/jobs";
    private static final String USER = "alice";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger executions = new AtomicInteger();
    private Connection keepAlive;
    private QueryJobMapper mapper;
    private QueryJobWorker worker;
    private ThreadPoolTaskExecutor executor;
    private MockMvc mvc;
    private volatile Supplier<R<QueryResult>> executeSql = () -> result(7);

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        keepAlive = database.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            String migration = Files.readString(Path.of("..", "db", "migrate-query-jobs.sql"))
                    .replace("USE data_khaos;", "");
            statement.execute(migration);
            statement.execute("""
                    CREATE TABLE query_history (
                      id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(32), datasource_id VARCHAR(32),
                      database_name VARCHAR(200), sql_text TEXT, status INT, cost_ms BIGINT,
                      row_count INT, error_message VARCHAR(1000), create_time TIMESTAMP
                    )
                    """);
        }
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), database));
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new StandardEnvironment()));
        configuration.addMapper(QueryJobMapper.class);
        configuration.addMapper(QueryHistoryMapper.class);
        var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = template.getMapper(QueryJobMapper.class);
        var queries = new QueryService(template.getMapper(QueryHistoryMapper.class), null, null, new QueryProperties());
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.initialize();
        worker = new QueryJobWorker(mapper, queries, json, executor, new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> request,
                                                  ParameterizedTypeReference<T> type, Object... vars) {
                if (url.contains("/api/permission/user/")) {
                    return (ResponseEntity<T>) ResponseEntity.ok(R.ok(new UserPermissionDto()));
                }
                assertTrue(url.contains("/api/ds/"));
                assertEquals(USER, MetadataHolder.getUserId());
                executions.incrementAndGet();
                return (ResponseEntity<T>) ResponseEntity.ok(executeSql.get());
            }
        });
        mvc = MockMvcBuilders.standaloneSetup(new QueryJobController(new QueryJobService(mapper, json)))
                .addFilters(new MetadataContextFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void closeDatabase() throws Exception {
        executor.shutdown();
        keepAlive.close();
    }

    @Test
    void persistsResultAndEnforcesOwnershipAcrossAllEndpoints() throws Exception {
        String id = submit();
        assertEquals("QUEUED", detail(id).path("status").asText());
        executor.submit(() -> worker.run(id)).get(5, TimeUnit.SECONDS);
        assertEquals("SUCCESS", detail(id).path("status").asText());
        assertTrue(detail(id).path("resultAvailable").asBoolean());
        assertEquals(7, request(get(JOBS + "/" + id + "/result").header(HEADER_USER_ID, USER))
                .at("/data/rows/0/n").asInt());
        assertEquals(1, request(get(JOBS).param("status", "SUCCESS").header(HEADER_USER_ID, USER))
                .at("/data/total").asInt());

        assertEquals(0, request(get(JOBS).header(HEADER_USER_ID, "bob")).at("/data/total").asInt());
        assertEquals(404, request(get(JOBS + "/" + id).header(HEADER_USER_ID, "bob")).path("code").asInt());
        assertEquals(404, request(get(JOBS + "/" + id + "/result").header(HEADER_USER_ID, "bob")).path("code").asInt());
        assertEquals(404, request(post(JOBS + "/" + id + "/cancel").header(HEADER_USER_ID, "bob")).path("code").asInt());
        assertEquals(401, request(get(JOBS)).path("code").asInt());
    }

    @Test
    void idempotencyAndConcurrentWorkersKeepOneExecution() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = submit(key, "SELECT 7").at("/data/id").asText();
        assertFalse(id.isBlank());
        assertEquals(id, submit(key, " SELECT 7; ").at("/data/id").asText());
        assertEquals(5002, submit(key, "SELECT 8").path("code").asInt());
        assertEquals(400, submit(UUID.randomUUID().toString(), "UPDATE example SET n = 9").path("code").asInt());
        runConcurrently(id);
        assertEquals(1, executions.get());
    }

    @Test
    void canCancelQueuedWorkAndDiscardResultsFromRunningWork() throws Exception {
        String queued = submit();
        assertEquals("CANCELLED", cancel(queued));
        worker.run(queued);
        assertEquals(0, executions.get());

        String running = submit();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executeSql = () -> {
            entered.countDown();
            await(release);
            return result(7);
        };
        var future = executor.submit(() -> worker.run(running));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED", cancel(running));
        } finally {
            release.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
        assertEquals("CANCELLED", detail(running).path("status").asText());
        assertFalse(detail(running).path("resultAvailable").asBoolean());
        assertEquals(5001, request(get(JOBS + "/" + running + "/result").header(HEADER_USER_ID, USER))
                .path("code").asInt());
    }

    @Test
    void recordsExecutionErrorsAndRecoversStaleJobsAndResults() throws Exception {
        String failing = submit();
        executeSql = () -> R.fail("测试数据源未就绪");
        worker.run(failing);
        assertEquals("FAILED", detail(failing).path("status").asText());
        assertEquals("测试数据源未就绪", detail(failing).path("errorMessage").asText());

        String large = submit();
        executeSql = () -> result("x".repeat(1_048_576));
        worker.run(large);
        assertTrue(detail(large).path("errorMessage").asText().contains("1 MiB"));

        String completed = submit();
        executeSql = () -> result(7);
        worker.run(completed);
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate().eq(QueryJob::getId, completed)
                .set(QueryJob::getResultExpiresAt, LocalDateTime.now().minusHours(1)));
        String interrupted = submit();
        mapper.update(null, Wrappers.<QueryJob>lambdaUpdate().eq(QueryJob::getId, interrupted)
                .set(QueryJob::getStatus, QueryJobStatus.RUNNING)
                .set(QueryJob::getStartTime, LocalDateTime.now().minusMinutes(11)));

        worker.expire();
        assertEquals("FAILED", detail(interrupted).path("status").asText());
        assertFalse(detail(completed).path("resultAvailable").asBoolean());
        assertEquals(404, request(get(JOBS + "/" + completed + "/result").header(HEADER_USER_ID, USER))
                .path("code").asInt());
        assertNull(mapper.selectOne(Wrappers.<QueryJob>lambdaQuery()
                .select(QueryJob::getId, QueryJob::getResultJson).eq(QueryJob::getId, completed)).getResultJson());
    }

    @Test
    void summarizesOnlyOwnedJobsBySubmissionDayAndDrillsIntoMatchingRecords() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime boundary = yesterday.atStartOfDay();
        insertUsageJob(USER, QueryJobStatus.SUCCESS, boundary, 4);
        insertUsageJob(USER, QueryJobStatus.SUCCESS, boundary, null);
        insertUsageJob(USER, QueryJobStatus.FAILED, boundary.plusHours(1), 50);
        insertUsageJob(USER, QueryJobStatus.CANCELLED, boundary.plusHours(2), null);
        insertUsageJob(USER, QueryJobStatus.CANCEL_REQUESTED, boundary.plusHours(3), null);
        insertUsageJob(USER, QueryJobStatus.QUEUED, boundary.plusDays(1), null);
        insertUsageJob(USER, QueryJobStatus.FAILED, boundary.minusSeconds(1), null);
        insertUsageJob("bob", QueryJobStatus.SUCCESS, boundary, 100);

        String endpoint = JOBS + "/usage";
        JsonNode days = request(get(endpoint).param("days", "2").header(HEADER_USER_ID, USER)).path("data");
        assertEquals(2, days.size(), days.toString());
        assertEquals(yesterday.toString(), days.get(0).path("submittedOn").asText());
        assertEquals(2, days.get(0).path("succeeded").asInt());
        assertEquals(1, days.get(0).path("failed").asInt());
        assertEquals(1, days.get(0).path("cancelled").asInt());
        assertEquals(1, days.get(0).path("active").asInt());
        assertEquals(1, days.get(0).path("timedSuccesses").asInt());
        assertEquals(4, days.get(0).path("executionSeconds").asInt());
        assertEquals(1, days.get(1).path("active").asInt());
        assertFalse(days.get(0).has("sqlText"));

        JsonNode empty = request(get(endpoint).header(HEADER_USER_ID, "charlie")).path("data");
        assertEquals(7, empty.size());
        assertEquals(0, empty.get(5).path("succeeded").asInt(-1));
        assertEquals(401, request(get(endpoint)).path("code").asInt());
        assertEquals(400, request(get(endpoint).param("days", "31").header(HEADER_USER_ID, USER)).path("code").asInt());

        var filtered = get(JOBS).param("submittedOn", yesterday.toString()).header(HEADER_USER_ID, USER);
        assertEquals(5, request(filtered).at("/data/total").asInt());
        assertEquals(1, request(filtered.param("status", "FAILED")).at("/data/total").asInt());
        assertEquals(1, request(get(JOBS).param("submittedOn", yesterday.toString()).header(HEADER_USER_ID, "bob"))
                .at("/data/total").asInt());
    }

    private void insertUsageJob(String owner, QueryJobStatus status, LocalDateTime submitted, Integer seconds) {
        var job = new QueryJob();
        job.setUserId(owner);
        job.setRequestId(UUID.randomUUID().toString());
        job.setDatasourceId("sample");
        job.setSqlText("SELECT 7");
        job.setCreateTime(submitted);
        job.setStatus(status);
        if (seconds != null) {
            job.setStartTime(submitted.plusSeconds(60));
            job.setEndTime(job.getStartTime().plusSeconds(seconds));
        }
        mapper.insert(job);
    }

    private void runConcurrently(String id) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var first = executor.submit(() -> {
            await(start);
            worker.run(id);
        });
        var second = executor.submit(() -> {
            await(start);
            worker.run(id);
        });
        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String submit() throws Exception {
        JsonNode response = submit(UUID.randomUUID().toString(), "SELECT 7");
        assertEquals(0, response.path("code").asInt(-1), response.toString());
        return response.at("/data/id").asText();
    }

    private JsonNode submit(String key, String sql) throws Exception {
        return request(post(JOBS).header(HEADER_USER_ID, USER).contentType("application/json")
                .content(json.writeValueAsString(Map.of("requestId", key, "datasourceId", "sample", "sql", sql))));
    }

    private JsonNode detail(String id) throws Exception {
        return request(get(JOBS + "/" + id).header(HEADER_USER_ID, USER)).path("data");
    }

    private String cancel(String id) throws Exception {
        return request(post(JOBS + "/" + id + "/cancel").header(HEADER_USER_ID, USER)).at("/data/status").asText();
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsByteArray());
    }

    private static R<QueryResult> result(Object value) {
        QueryResult result = new QueryResult();
        ColumnInfo column = new ColumnInfo();
        column.setColumnName("n");
        result.setColumns(List.of(column));
        result.setRows(List.of(Map.of("n", value)));
        result.setRowCount(1);
        return R.ok(result);
    }
}
