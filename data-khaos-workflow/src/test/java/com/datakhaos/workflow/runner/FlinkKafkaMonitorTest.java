package com.datakhaos.workflow.runner;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.flink.FlinkRuntimeClient.KafkaReading;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkKafkaMonitorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);
    private static final String VERTEX = "a".repeat(32);
    private JdbcTemplate jdbc;
    private FlinkAlertOutbox outbox;
    private FlinkKafkaMonitor monitor;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() throws Exception {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        outbox = new FlinkAlertOutbox(jdbc, "test-token");
        monitor = new FlinkKafkaMonitor(jdbc, outbox, true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("""
                CREATE TABLE flink_deployment (
                    id VARCHAR(32) PRIMARY KEY, project_id VARCHAR(32), cluster_key VARCHAR(64),
                    state VARCHAR(24), engine_job_id CHAR(32), operator_id VARCHAR(32)
                )
                """);
        for (String name : List.of("migrate-flink-kafka-alert.sql", "migrate-flink-alert-notification.sql")) {
            var migration = Files.readString(Path.of("..", "db", name))
                    .replace("USE data_khaos;", "").replace(" ENGINE=InnoDB", "");
            for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO flink_deployment VALUES ('run','project','cluster','SUBMITTED','engine','owner')");
    }

    @Test
    void highAndRecoveryRequireConsecutiveSamplesAndDeliverInOrder() {
        var rule = enable();
        sample(rule, 0, 170L);
        sample(rule, 5, 190L);
        sample(rule, 30, 150L);
        assertTrue(history().alerts().isEmpty());
        Runnable third = () -> sample(rule, 60, 180L);
        CompletableFuture.allOf(CompletableFuture.runAsync(third), CompletableFuture.runAsync(third)).join();
        assertEquals(1, history().alerts().size());
        assertEquals("OPEN", history().alerts().get(0).status());
        sample(rule, 90, 250L);
        sample(rule, 120, 0L);
        sample(rule, 150, null);
        assertNull(history().watch().lastLag());
        sample(rule, 180, 2L);
        assertEquals("OPEN", history().alerts().get(0).status());
        sample(rule, 210, 0L);
        assertEquals(0L, history().watch().lastLag());
        assertEquals("RECOVERED", history().alerts().get(0).status());
        assertEquals(250L, history().alerts().get(0).peakLag());

        var open = outbox.due(NOW).get(0);
        assertEquals(FlinkAlertEvent.Type.KAFKA_OPEN, open.event().type());
        assertEquals("owner", open.event().receiverId());
        outbox.delivered(open, NOW);
        assertEquals(FlinkAlertEvent.Type.KAFKA_CLEAR, outbox.due(NOW).get(0).event().type());
    }

    @Test
    void staleRuleCannotAffectReplacementAndEndedRunStopsSampling() {
        var first = enable();
        assertThrows(BusinessException.class, this::enable);
        sample(first, 0, 180L);
        sample(first, 30, 180L);
        sample(first, 60, 180L);
        transaction.executeWithoutResult(tx -> monitor.disable("run", "cluster", "engine", NOW));
        assertEquals("DISABLED", history().alerts().get(0).status());
        assertFalse(history().watch().enabled());
        var second = enable();
        sample(first, 90, 680L);
        assertNull(history().watch().lastLag());
        sample(second, 100, 130L);
        sample(second, 290, 130L);
        sample(second, 320, 130L);
        assertEquals(1, history().alerts().size());
        transaction.executeWithoutResult(tx -> monitor.record(second, "cluster",
                new KafkaReading("FINISHED", null, false, null), 350_000, NOW));
        assertFalse(history().watch().enabled());
        assertTrue(monitor.candidates("cluster", "").isEmpty());
    }

    private FlinkKafkaMonitor.Candidate enable() {
        transaction.executeWithoutResult(tx -> monitor.enable("run", "cluster", "engine", VERTEX, "Kafka source", 100, 10));
        return monitor.candidates("cluster", "").get(0);
    }

    private FlinkKafkaMonitor.History history() {
        return monitor.history("run");
    }

    private void sample(FlinkKafkaMonitor.Candidate candidate, long seconds, Long lag) {
        transaction.executeWithoutResult(tx -> monitor.record(candidate, "cluster",
                new KafkaReading("RUNNING", "Kafka source", true, lag), 1000 + seconds * 1000, NOW));
    }
}
