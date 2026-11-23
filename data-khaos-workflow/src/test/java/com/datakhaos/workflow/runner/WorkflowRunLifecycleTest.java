package com.datakhaos.workflow.runner;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunLifecycleTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 12, 0);
    private JdbcTemplate jdbc;
    private WorkflowRunLifecycle lifecycle;
    private TransactionTemplate transaction;

    @BeforeEach
    void init() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        lifecycle = new WorkflowRunLifecycle(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("""
                CREATE TABLE workflow_run(id VARCHAR(32) PRIMARY KEY, run_status VARCHAR(25), heartbeat_at TIMESTAMP,
                    start_time TIMESTAMP, create_time TIMESTAMP, end_time TIMESTAMP, duration_ms BIGINT,
                    error_message VARCHAR(500), recovery_code VARCHAR(40))
                """);
        jdbc.execute("CREATE TABLE workflow_node_run(id VARCHAR(32), run_id VARCHAR(32), status VARCHAR(25), start_time TIMESTAMP, end_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE workflow_bizdate_lock(run_id VARCHAR(32))");
    }

    @Test
    void expiredPendingRunIsFailedAndDelayedRunnerCannotStart() {
        seed("PENDING", "PENDING");
        Runnable scan = () -> transaction.executeWithoutResult(tx -> lifecycle.expire("run", NOW.minusMinutes(5), NOW));
        CompletableFuture.allOf(CompletableFuture.runAsync(scan), CompletableFuture.runAsync(scan)).join();
        assertEquals("FAILED", runStatus());
        assertEquals("SKIPPED", nodeStatus());
        assertEquals(0, locks());
        assertFalse(lifecycle.start("run", NOW));
    }

    @Test
    void expiredRunningWorkKeepsLockAndRejectsLateNodeStartAndFinish() {
        seed("RUNNING", "RUNNING");
        assertEquals(1, lifecycle.expired(NOW.minusMinutes(5)).size());
        transaction.executeWithoutResult(tx -> lifecycle.expire("run", NOW.minusMinutes(5), NOW));
        assertEquals("RECOVERY_REQUIRED", runStatus());
        assertEquals(1, locks());
        assertEquals("RUNNING", nodeStatus());
        assertEquals(Boolean.FALSE, transaction.execute(tx -> lifecycle.startNode("run", "node", NOW)));
        transaction.executeWithoutResult(tx -> lifecycle.finish("run", "SUCCESS", null, 1, false, NOW));
        assertEquals(1, locks());
        assertEquals("RECOVERY_REQUIRED", runStatus());
    }

    @Test
    void freshHeartbeatWinsAndUnknownSubmissionRetainsLock() {
        seed("PENDING", "PENDING");
        assertTrue(lifecycle.start("run", NOW));
        assertEquals(Boolean.TRUE, transaction.execute(tx -> lifecycle.startNode("run", "node", NOW)));
        lifecycle.heartbeat("run", NOW.plusMinutes(1));
        transaction.executeWithoutResult(tx -> lifecycle.expire("run", NOW, NOW.plusMinutes(1)));
        assertEquals("RUNNING", runStatus());
        transaction.executeWithoutResult(tx -> lifecycle.finish("run", "RECOVERY_REQUIRED", "unknown", 1, true, NOW));
        assertEquals("RECOVERY_REQUIRED", runStatus());
        assertEquals("SUBMISSION_UNCONFIRMED", jdbc.queryForObject("SELECT recovery_code FROM workflow_run", String.class));
        assertEquals(1, locks());
    }

    private void seed(String run, String node) {
        jdbc.update("INSERT INTO workflow_run (id, run_status, create_time, start_time) VALUES ('run', ?, ?, ?)",
                run, NOW.minusMinutes(10), NOW.minusMinutes(10));
        jdbc.update("INSERT INTO workflow_node_run (id, run_id, status) VALUES ('node', 'run', ?)", node);
        jdbc.update("INSERT INTO workflow_bizdate_lock VALUES ('run')");
    }

    private String runStatus() {
        return jdbc.queryForObject("SELECT run_status FROM workflow_run", String.class);
    }

    private String nodeStatus() {
        return jdbc.queryForObject("SELECT status FROM workflow_node_run", String.class);
    }

    private int locks() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_bizdate_lock", Integer.class);
    }
}
