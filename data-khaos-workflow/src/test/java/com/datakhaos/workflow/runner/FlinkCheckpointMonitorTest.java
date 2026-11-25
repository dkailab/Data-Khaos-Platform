package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.flink.FlinkCheckpointView;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.flink.FlinkCheckpointView.Entry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlinkCheckpointMonitorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 9, 0);
    private JdbcTemplate jdbc;
    private FlinkCheckpointMonitor monitor;
    private FlinkAlertOutbox outbox;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        outbox = new FlinkAlertOutbox(jdbc, "test-token");
        monitor = new FlinkCheckpointMonitor(jdbc, outbox, true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("""
                CREATE TABLE flink_deployment (
                    id VARCHAR(32) PRIMARY KEY, project_id VARCHAR(32), cluster_key VARCHAR(64),
                    state VARCHAR(24), engine_job_id CHAR(32), operator_id VARCHAR(32)
                )
                """);
        for (String name : List.of("migrate-flink-checkpoint-alert.sql", "migrate-flink-alert-notification.sql")) {
            var migration = Files.readString(Path.of("..", "db", name))
                    .replace("USE data_khaos;", "").replace(" ENGINE=InnoDB", "");
            for (String statement : migration.split(";")) {
                if (!statement.isBlank()) jdbc.execute(statement);
            }
        }
        jdbc.update("INSERT INTO flink_deployment VALUES ('run','project','cluster','SUBMITTED','engine','owner')");
    }

    @Test
    void replicasDoNotDuplicateAnAlertAndUnknownSamplesDoNotRecoverIt() {
        var threeFailures = sample(failed(1), failed(2), failed(3));
        Runnable scan = () -> record(threeFailures);
        CompletableFuture.allOf(CompletableFuture.runAsync(scan), CompletableFuture.runAsync(scan)).join();
        var alert = monitor.history("run").alerts().get(0);
        assertEquals("OPEN", alert.status());
        assertEquals(1L, alert.firstFailedCheckpointId());
        assertEquals(3L, alert.lastFailedCheckpointId());
        assertEquals(1, monitor.history("run").alerts().size());
        assertEquals(1, outbox.due(NOW).size());
        record(null);
        record(sample(completed(2)));
        assertEquals("OPEN", monitor.history("run").alerts().get(0).status());
    }

    @Test
    void newerSuccessRecoversTheOldEpisodeEvenWithNewFailuresInTheSamePoll() {
        record(sample(failed(1), failed(2), failed(3)));
        record(sample(failed(3), completed(4), failed(5), failed(6), failed(7)));
        var alerts = monitor.history("run").alerts();
        assertEquals(2, alerts.size());
        var recovered = alerts.stream().filter(alert -> alert.status().equals("RECOVERED")).findFirst().orElseThrow();
        assertEquals(4L, recovered.recoveryCheckpointId());
        var open = alerts.stream().filter(alert -> alert.status().equals("OPEN")).findFirst().orElseThrow();
        assertEquals(5L, open.firstFailedCheckpointId());
        assertEquals(7L, open.lastFailedCheckpointId());
    }

    @Test
    void savepointsCannotOpenOrRecoverAlertsAndTerminalRunsCannotReopen() {
        record(sample(failed(1), failed(2), new Entry(3, "FAILED", true, null, null, null)));
        assertTrue(monitor.history("run").alerts().isEmpty());
        record(sample(failed(1), failed(2), failed(4)));
        record(sample(new Entry(5, "COMPLETED", true, null, null, null)));
        assertEquals("OPEN", monitor.history("run").alerts().get(0).status());
        jdbc.update("UPDATE flink_deployment SET state = 'STOPPED' WHERE id = 'run'");
        assertEquals(1, monitor.candidates("cluster", "").size());
        record(sample(completed(6)));
        assertEquals("ENDED", monitor.history("run").alerts().get(0).status());
        assertTrue(monitor.candidates("cluster", "").isEmpty());
        record(sample(failed(7), failed(8), failed(9)));
        assertEquals(1, monitor.history("run").alerts().size());
    }

    @Test
    void wrongClusterAndDuplicateCheckpointEvidenceCannotTriggerAnAlert() {
        transaction.executeWithoutResult(tx -> monitor.record("run", "other-cluster", "engine",
                sample(failed(1), failed(2), failed(3)), false, NOW));
        record(sample(failed(1), failed(2), new Entry(2, "FAILED", false, null, 500L, null)));
        assertTrue(monitor.history("run").alerts().isEmpty());
        assertFalse(new FlinkCheckpointMonitor(jdbc, new FlinkAlertOutbox(jdbc, ""), false).history("run").enabled());
    }

    @Test
    void retriesAndDeliversOpenBeforeRecoveryEvenAfterRestart() {
        record(sample(failed(1), failed(2), failed(3)));
        record(sample(completed(4)));
        var pending = outbox.due(NOW);
        assertEquals(1, pending.size());
        var open = pending.get(0);
        assertEquals(FlinkAlertEvent.Type.OPEN, open.event().type());
        assertEquals("owner", open.event().receiverId());
        outbox.retry(open, NOW);
        outbox.retry(open, NOW);
        assertTrue(outbox.due(NOW.plusSeconds(9)).isEmpty());
        var restarted = new FlinkAlertOutbox(jdbc, "test-token");
        var retry = restarted.due(NOW.plusSeconds(10)).get(0);
        assertEquals(open.id(), retry.id());
        assertEquals(1, retry.attempts());
        restarted.delivered(retry, NOW.plusSeconds(10));
        var recovered = restarted.due(NOW.plusSeconds(10)).get(0);
        assertEquals(FlinkAlertEvent.Type.RECOVERED, recovered.event().type());
        assertEquals(open.event().alertId(), recovered.event().alertId());
    }

    @Test
    void rollsBackAlertAndSamplingStateWhenEnqueueFails() {
        jdbc.execute("ALTER TABLE flink_alert_outbox ADD CONSTRAINT reject_test CHECK (receiver_id <> 'owner')");
        assertThrows(RuntimeException.class,
                () -> record(sample(failed(1), failed(2), failed(3))));
        assertTrue(monitor.history("run").alerts().isEmpty());
        jdbc.execute("ALTER TABLE flink_alert_outbox DROP CONSTRAINT reject_test");
        record(sample(failed(1), failed(2), failed(3)));
        assertEquals(1, outbox.due(NOW).size());
    }

    private void record(FlinkCheckpointView sample) {
        transaction.executeWithoutResult(tx -> monitor.record("run", "cluster", "engine", sample, false, NOW));
    }

    private static FlinkCheckpointView sample(Entry... entries) {
        return new FlinkCheckpointView(true, null, null, null, null, List.of(entries));
    }

    private static Entry failed(long id) {
        return new Entry(id, "FAILED", false, null, null, null);
    }

    private static Entry completed(long id) {
        return new Entry(id, "COMPLETED", false, null, null, null);
    }
}
