package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.flink.FlinkPressureSample;
import com.datakhaos.workflow.flink.FlinkPressureSample.Counter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkPressureMonitorTest {
    private static final String VERTEX = "a".repeat(32);
    private static final String OTHER = "b".repeat(32);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);
    private JdbcTemplate jdbc;
    private FlinkPressureMonitor monitor;
    private FlinkAlertOutbox outbox;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        outbox = new FlinkAlertOutbox(jdbc, "test-token");
        monitor = new FlinkPressureMonitor(jdbc, new ObjectMapper(), outbox, true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("""
                CREATE TABLE flink_deployment (
                    id VARCHAR(32) PRIMARY KEY, project_id VARCHAR(32), cluster_key VARCHAR(64),
                    state VARCHAR(24), engine_job_id CHAR(32), operator_id VARCHAR(32)
                )
                """);
        for (String name : List.of("migrate-flink-pressure-alert.sql", "migrate-flink-alert-notification.sql")) {
            var migration = Files.readString(Path.of("..", "db", name))
                    .replace("USE data_khaos;", "").replace(" ENGINE=InnoDB", "");
            for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO flink_deployment VALUES ('run','project','cluster','SUBMITTED','engine','owner')");
    }

    @Test
    void repeatedPollsDoNotOpenEarlyAndIdleWindowsRecoverAfterSustainedPressure() {
        record(sample(0, 0));
        record(sample(30, 36_000));
        record(sample(40, 48_000));
        record(sample(60, 72_000));
        assertTrue(monitor.history("run").alerts().isEmpty());

        Runnable third = () -> record(sample(90, 114_000));
        CompletableFuture.allOf(CompletableFuture.runAsync(third), CompletableFuture.runAsync(third)).join();
        var history = monitor.history("run").alerts();
        assertEquals(1, history.size());
        assertEquals(70d, history.get(0).peakPercent(), 0.00001);
        assertEquals(VERTEX, history.get(0).vertexId());
        assertEquals(FlinkAlertEvent.Type.PRESSURE_OPEN, outbox.due(NOW).get(0).event().type());

        record(sample(120, 114_000));
        assertEquals("OPEN", monitor.history("run").alerts().get(0).status());
        record(sample(150, 114_000));
        assertEquals("RECOVERED", monitor.history("run").alerts().get(0).status());
        var open = outbox.due(NOW).get(0);
        outbox.delivered(open, NOW);
        assertEquals(FlinkAlertEvent.Type.PRESSURE_CLEAR, outbox.due(NOW).get(0).event().type());
    }

    @Test
    void missingCountersAndRestartDoNotCloseAnActiveAlarm() {
        record(sample(0, 0));
        record(sample(30, 36_000));
        record(sample(60, 72_000));
        record(sample(90, 108_000));
        record(new FlinkPressureSample("RUNNING", 121_000, Map.of(
                VERTEX, new Counter(2, 100, 0, true), OTHER, new Counter(1, 100, -1, true))));
        record(new FlinkPressureSample("RUNNING", 151_000, Map.of(
                VERTEX, new Counter(2, 100, 0, true), OTHER, new Counter(1, 100, -1, true))));
        record(new FlinkPressureSample("RUNNING", 181_000, Map.of(
                VERTEX, new Counter(2, 100, 0, true), OTHER, new Counter(1, 100, -1, true))));
        assertEquals("OPEN", monitor.history("run").alerts().get(0).status());
        record(new FlinkPressureSample("FAILED", 211_000, Map.of()));
        assertEquals("ENDED", monitor.history("run").alerts().get(0).status());
        assertTrue(monitor.candidates("cluster", "").isEmpty());
    }

    @Test
    void longSampleGapCannotCompleteAnOldHighStreak() {
        record(sample(0, 0));
        record(sample(30, 36_000));
        record(sample(60, 72_000));
        record(sample(240, 300_000));
        record(sample(270, 336_000));
        assertTrue(monitor.history("run").alerts().isEmpty());
    }

    private static FlinkPressureSample sample(long seconds, long accumulated) {
        return new FlinkPressureSample("RUNNING", 1_000 + seconds * 1_000,
                Map.of(VERTEX, new Counter(2, 100, accumulated, true)));
    }

    private void record(FlinkPressureSample sample) {
        transaction.executeWithoutResult(tx -> monitor.record("run", "cluster", "engine", sample, NOW));
    }
}
