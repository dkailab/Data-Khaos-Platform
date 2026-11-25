package com.datakhaos.workflow.runner;

import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.flink.FlinkEventTimeSample;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkEventTimeMonitorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);
    private static final Instant BASE = Instant.ofEpochMilli(1_764_116_000_000L);
    private static final String VERTEX = "a".repeat(32);
    private JdbcTemplate jdbc;
    private FlinkAlertOutbox outbox;
    private FlinkEventTimeMonitor monitor;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() throws Exception {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        outbox = new FlinkAlertOutbox(jdbc, "test-token");
        monitor = new FlinkEventTimeMonitor(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), outbox, true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("""
                CREATE TABLE flink_deployment (
                    id VARCHAR(32) PRIMARY KEY, project_id VARCHAR(32), cluster_key VARCHAR(64),
                    state VARCHAR(24), engine_job_id CHAR(32), operator_id VARCHAR(32)
                )
                """);
        for (String name : List.of("migrate-flink-event-time-alert.sql", "migrate-flink-alert-notification.sql")) {
            var migration = Files.readString(Path.of("..", "db", name))
                    .replace("USE data_khaos;", "").replace(" ENGINE=InnoDB", "");
            for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO flink_deployment VALUES ('run','project','cluster','SUBMITTED','engine','owner')");
    }

    @Test
    void requiresInputAndPreservesAnOpenAlertUntilTheWatermarkPassesItsStallPosition() {
        var rule = configure(true);
        sample(rule, 0, 100, 60, 0);
        sample(rule, 1, 100, 60, 0);
        assertEquals("IDLE", history().points().get(0).diagnosis());
        sample(rule, 2, 130, 60, 0);
        missing(rule, 3);
        assertEquals("UNKNOWN", history().points().get(0).diagnosis());
        sample(rule, 4, 160, 60, 0);
        sample(rule, 5, 170, 60, 0);
        sample(rule, 6, 180, 60, 0);
        assertTrue(history().alerts().isEmpty());
        Runnable last = () -> sample(rule, 7, 190, 60, 0);
        CompletableFuture.allOf(CompletableFuture.runAsync(last), CompletableFuture.runAsync(last)).join();
        assertEquals(1, history().alerts().size());
        assertEquals("OPEN", history().alerts().get(0).status());
        assertEquals("STALLED", history().points().get(0).diagnosis());
        assertEquals(8, history().points().size());

        sample(rule, 8, 10, 0, 8);
        sample(rule, 9, 20, 15, 8);
        assertEquals("OPEN", history().alerts().get(0).status());
        sample(rule, 10, 30, 61, 8);
        assertEquals("RECOVERED", history().alerts().get(0).status());
        var opening = outbox.due(NOW).get(0);
        assertEquals(FlinkAlertEvent.Type.WMARK_OPEN, opening.event().type());
        outbox.delivered(opening, NOW);
        assertEquals(FlinkAlertEvent.Type.WMARK_CLEAR, outbox.due(NOW).get(0).event().type());
    }

    @Test
    void sampleHistoryIsBoundedAndAReplacedWatchDoesNotAcceptOldPolls() {
        var old = configure(true);
        for (int i = 0; i < 125; i++) sample(old, i, 100 + i, i, 0);
        assertEquals(120, history().points().size());
        assertEquals(BASE.plusSeconds(5 * 30L).toEpochMilli(), history().points().get(119).sampledAtMs());
        configure(false);
        assertFalse(history().watch().enabled());
        var replacement = configure(true);
        sample(old, 125, 9999, 130, 0);
        assertEquals(BASE.plusSeconds(124 * 30L).toEpochMilli(), history().points().get(0).sampledAtMs());
        sample(replacement, 126, 9999, 130, 0);
        assertEquals("BASELINE", history().points().get(0).diagnosis());
        jdbc.update("UPDATE flink_deployment SET state = 'STOPPED' WHERE id = 'run'");
        missing(replacement, 127);
        assertFalse(history().watch().enabled());
        assertTrue(monitor.candidates("cluster", "").isEmpty());
    }

    private FlinkEventTimeMonitor.Candidate configure(boolean enabled) {
        transaction.executeWithoutResult(tx -> monitor.configure("run", "cluster", "engine", VERTEX, enabled, NOW));
        return enabled ? monitor.candidates("cluster", "").get(0) : null;
    }

    private FlinkEventTimeMonitor.History history() {
        return monitor.history("run");
    }

    private void sample(FlinkEventTimeMonitor.Candidate rule, int interval, long input, int watermark, int generation) {
        Instant at = BASE.plusSeconds(interval * 30L);
        var payload = new FlinkEventTimeSample(VERTEX, "RUNNING", at, BASE.minusSeconds(300).plusSeconds(generation),
                1, input, BASE.plusSeconds(watermark));
        transaction.executeWithoutResult(tx -> monitor.record(rule, "cluster", payload, at.toEpochMilli(), NOW));
    }

    private void missing(FlinkEventTimeMonitor.Candidate rule, int interval) {
        transaction.executeWithoutResult(tx -> monitor.record(rule, "cluster", null,
                BASE.plusSeconds(interval * 30L).toEpochMilli(), NOW));
    }
}
