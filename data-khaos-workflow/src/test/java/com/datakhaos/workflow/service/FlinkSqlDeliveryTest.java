package com.datakhaos.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.AuthUser;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkDefinition;
import com.datakhaos.workflow.flink.FlinkRunView;
import com.datakhaos.workflow.flink.FlinkRuntimeClient;
import com.datakhaos.workflow.mapper.FlinkAssetMapper;
import com.datakhaos.workflow.mapper.FlinkAssetVersionMapper;
import com.datakhaos.workflow.mapper.FlinkDeploymentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FlinkSqlDeliveryTest {
    private static final String PROJECT = "flink-test";
    private static final String VALID_SQL = """
            CREATE TEMPORARY TABLE orders (id BIGINT) WITH ('connector' = 'datagen', 'rows-per-second' = '2');
            CREATE TEMPORARY TABLE serving (id BIGINT) WITH ('connector' = 'blackhole');
            INSERT INTO serving SELECT id FROM orders
            """;
    private static final String INVALID_SQL = VALID_SQL.replace("SELECT id", "SELECT missing_column");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final WorkflowProjectAccess access = new WorkflowProjectAccess(null, null) {
        @Override
        public void requireAction(String project, String capability) {
            assertEquals(PROJECT, project);
        }
    };
    private JdbcTemplate jdbc;
    private FlinkDeploymentMapper runs;
    private FlinkAssetMapper assets;
    private FlinkStudioService studio;

    @BeforeEach
    void setUp() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database);
        for (String migration : List.of("migrate-flink-studio.sql", "migrate-flink-deployment.sql")) {
            String sql = Files.readString(Path.of("..", "db", migration)).replace("USE data_khaos;", "");
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) jdbc.execute(statement);
            }
        }
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("flink", new SpringManagedTransactionFactory(), database));
        config.addMapper(FlinkAssetMapper.class);
        config.addMapper(FlinkAssetVersionMapper.class);
        config.addMapper(FlinkDeploymentMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        assets = session.getMapper(FlinkAssetMapper.class);
        runs = session.getMapper(FlinkDeploymentMapper.class);
        studio = new FlinkStudioService(assets, session.getMapper(FlinkAssetVersionMapper.class), access, json);
        MetadataHolder.set(AuthUser.builder().id("developer").build());
    }

    @AfterEach
    void clearUser() {
        MetadataHolder.clear();
    }

    @Test
    void rejectsInvalidInsertBeforeDispatchAndAllowsCorrectedVersion() {
        var inserts = new AtomicInteger();
        var closed = new AtomicInteger();
        var runtime = new FlinkRuntimeClient(json, PROJECT, "http://localhost:8083", "http://localhost:8081", "") {
            @Override public String open(String name, String recovery) { return "session"; }
            @Override public void awaitSetup(String session, String statement, long deadline) { }
            @Override public void awaitInsertPlan(String session, String insert, long deadline) {
                if (insert.contains("missing_column")) throw new RuntimeFailure("Flink 无法生成 INSERT 执行计划", true);
            }
            @Override public Optional<String> insert(String session, String insert, long deadline) {
                inserts.incrementAndGet();
                return Optional.of("a".repeat(32));
            }
            @Override public boolean readyToStop(String id) { return false; }
            @Override public void stopWithSavepoint(String id, String triggerId) { fail("未就绪作业不得发送保存点请求"); }
            @Override public void close(String session) { closed.incrementAndGet(); }
        };
        var service = service(runtime);
        var asset = studio.create(PROJECT, FlinkAsset.Kind.JOB, "orders", "Orders", "Initial", definition(INVALID_SQL));
        var validation = assertThrows(BusinessException.class, () -> service.validateSql(PROJECT, asset.assetId(), 1));
        assertTrue(validation.getMessage().contains("INSERT 校验失败"));
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), validation.getCode());
        assertEquals(0, runs.selectCount(null));

        String failedKey = key();
        var failed = service.submit(PROJECT, asset.assetId(), 1, failedKey);
        assertEquals(State.FAILED, failed.state());
        assertNull(failed.engineJobId());
        assertNull(runs.selectById(failed.id()).getActiveAssetId());
        assertEquals(failed.id(), service.submit(PROJECT, asset.assetId(), 1, failedKey).id());
        assertEquals(0, inserts.get());

        var corrected = studio.save(PROJECT, asset.assetId(), 1, "Orders", "Fix", definition(VALID_SQL));
        assertEquals(2, service.validateSql(PROJECT, asset.assetId(), corrected.currentVersion()).version());
        var submitted = service.submit(PROJECT, asset.assetId(), 2, key());
        assertEquals(State.SUBMITTED, submitted.state());
        assertEquals(1, inserts.get());
        assertEquals(4, closed.get());
        var notReady = assertThrows(BusinessException.class,
                () -> service.stopWithSavepoint(PROJECT, asset.assetId(), submitted.id()));
        assertEquals(409, notReady.getCode());
        assertEquals(State.SUBMITTED, runs.selectById(submitted.id()).getState());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DK_FLINK_SQL_GATEWAY", matches = "https?://.+")
    void realFlinkValidatesStartsStopsAndRestoresFromSavepoint() throws Exception {
        var runtime = new FlinkRuntimeClient(json, PROJECT, System.getenv("DK_FLINK_SQL_GATEWAY"),
                System.getenv("DK_FLINK_JOBMANAGER"), "");
        var service = service(runtime);
        var asset = studio.create(PROJECT, FlinkAsset.Kind.JOB, "orders", "Orders", "Initial", definition(INVALID_SQL));
        String id = asset.assetId();
        var invalid = assertThrows(BusinessException.class, () -> service.validateSql(PROJECT, id, 1));
        assertTrue(invalid.getMessage().contains("INSERT 校验失败"), invalid::getMessage);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), invalid.getCode());
        assertEquals(State.FAILED, service.submit(PROJECT, id, 1, key()).state());

        studio.save(PROJECT, id, 1, "Orders", "Fix", definition(VALID_SQL));
        assertEquals("STREAMING", service.validateSql(PROJECT, id, 2).mode());
        String request = key();
        var submitted = service.submit(PROJECT, id, 2, request);
        assertEquals(submitted.id(), service.submit(PROJECT, id, 2, request).id());
        var running = await(service, id, submitted.id(), row -> "RUNNING".equals(row.engineState()) && runtime.readyToStop(row.engineJobId()));
        FlinkRunView active = running;
        try {
            await(service, id, running.id(), row -> service.checkpoints(PROJECT, id, row.id()).latestCompleted() != null);
            service.stopWithSavepoint(PROJECT, id, running.id());
            var stopped = await(service, id, running.id(), row -> row.state().terminal());
            assertEquals(State.STOPPED, stopped.state(), stopped::message);
            assertTrue(stopped.recoverable());

            active = service.resume(PROJECT, id, stopped.id(), key());
            var restored = await(service, id, active.id(), row -> "RUNNING".equals(row.engineState()));
            assertEquals(stopped.id(), restored.restoredFromRunId());
            assertNotEquals(running.engineJobId(), restored.engineJobId());
            assertEquals(2, restored.version());
            assertNotNull(service.checkpoints(PROJECT, id, restored.id()).latestRestore());
        } finally {
            var refreshed = service.refresh(PROJECT, id, active.id());
            if (!refreshed.state().terminal() && refreshed.engineJobId() != null) {
                service.cancel(PROJECT, id, refreshed.id());
                await(service, id, refreshed.id(), row -> row.state().terminal());
            }
        }
    }

    private FlinkDeploymentService service(FlinkRuntimeClient runtime) {
        return new FlinkDeploymentService(runs, assets, studio, access, runtime, null, null, null, null);
    }

    private FlinkDefinition definition(String sql) {
        return json.convertValue(java.util.Map.of("mode", "STREAMING", "parallelism", 1, "sql", sql), FlinkDefinition.class);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static FlinkRunView await(FlinkDeploymentService service, String assetId, String runId,
                                      Predicate<FlinkRunView> ready) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        FlinkRunView last;
        do {
            last = service.refresh(PROJECT, assetId, runId);
            if (ready.test(last)) return last;
            if (last.state().terminal()) fail("作业在达到期望状态前终止：" + last);
            Thread.sleep(500);
        } while (System.nanoTime() - deadline < 0);
        return fail("作业状态等待超时：" + last);
    }
}
