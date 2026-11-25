package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkPressureSample;
import com.datakhaos.workflow.flink.FlinkRuntimeClient.KafkaReading;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FlinkKafkaMonitor {
    static final int BATCH_SIZE = 12;
    private static final long MIN_INTERVAL_MS = 20_000;
    private static final long MAX_INTERVAL_MS = 120_000;
    private static final RowMapper<Watch> WATCH = (rs, row) -> new Watch(rs.getString("vertex_id"),
            rs.getString("vertex_name"), rs.getLong("high_threshold"), rs.getLong("low_threshold"),
            rs.getBoolean("enabled"), rs.getObject("last_lag", Long.class), rs.getLong("sampled_at_ms"));
    private final JdbcTemplate jdbc;
    private final FlinkAlertOutbox outbox;
    private final boolean enabled;

    public FlinkKafkaMonitor(JdbcTemplate jdbc, FlinkAlertOutbox outbox,
                             @Value("${FLINK_KAFKA_ALERT_ENABLED:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) throw new BusinessException(ResultCode.FORBIDDEN, "Kafka 积压巡检未启用");
    }

    public History history(String runId) {
        if (!enabled) return new History(false, null, List.of());
        var watch = jdbc.query("SELECT * FROM flink_kafka_watch WHERE run_id = ?", WATCH, runId);
        var alerts = jdbc.query("""
                SELECT id, vertex_id, vertex_name, peak_lag, status, opened_at, closed_at
                FROM flink_kafka_alert WHERE run_id = ? ORDER BY opened_at DESC, id DESC LIMIT 30
                """, (rs, row) -> new Alert(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class)), runId);
        return new History(true, watch.isEmpty() ? null : watch.get(0), alerts);
    }

    @Transactional
    public void enable(String runId, String cluster, String jobId, String vertex, String vertexName, long high, long low) {
        requireEnabled();
        var owner = lockRun(runId, cluster, jobId);
        if (owner == null || owner.state() != State.SUBMITTED) {
            throw new BusinessException(409, "仅已提交的运行可以启用 Kafka 积压告警");
        }
        var revision = jdbc.queryForList("SELECT enabled FROM flink_kafka_watch WHERE run_id = ?", Boolean.class, runId);
        if (!revision.isEmpty() && Boolean.TRUE.equals(revision.get(0))) {
            throw new BusinessException(409, "此运行已有 Kafka 告警规则，请先停用现有规则");
        }
        if (revision.isEmpty()) {
            jdbc.update("""
                    INSERT INTO flink_kafka_watch (revision_id, vertex_id, vertex_name, high_threshold, low_threshold, enabled, run_id)
                    VALUES (?, ?, ?, ?, ?, TRUE, ?)
                    """, IdWorker.getIdStr(), vertex, vertexName, high, low, runId);
        } else {
            jdbc.update("""
                    UPDATE flink_kafka_watch SET revision_id = ?, vertex_id = ?, vertex_name = ?, high_threshold = ?,
                    low_threshold = ?, enabled = TRUE, sampled_at_ms = 0, last_lag = NULL, high_streak = 0, low_streak = 0
                    WHERE run_id = ?
                    """, IdWorker.getIdStr(), vertex, vertexName, high, low, runId);
        }
    }

    @Transactional
    public void disable(String runId, String cluster, String jobId, LocalDateTime now) {
        requireEnabled();
        if (lockRun(runId, cluster, jobId) != null) stop(runId, "DISABLED", now);
    }

    List<Candidate> candidates(String cluster, String after) {
        return jdbc.query("""
                SELECT r.id, r.project_id, r.engine_job_id, r.state, w.revision_id, w.vertex_id
                FROM flink_kafka_watch w JOIN flink_deployment r ON r.id = w.run_id
                WHERE w.enabled = TRUE AND r.cluster_key = ? AND w.run_id > ? AND r.engine_job_id IS NOT NULL
                ORDER BY w.run_id LIMIT ?
                """, (rs, row) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3),
                State.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6)), cluster, after, BATCH_SIZE);
    }

    @Transactional
    public void record(Candidate candidate, String cluster, KafkaReading reading, long sampledAtMs, LocalDateTime now) {
        String runId = candidate.id();
        var owner = lockRun(runId, cluster, candidate.jobId());
        if (owner == null) return;
        var rows = jdbc.query("""
                SELECT vertex_id, vertex_name, high_threshold, low_threshold, sampled_at_ms, high_streak, low_streak
                FROM flink_kafka_watch WHERE run_id = ? AND revision_id = ? AND enabled = TRUE
                """, (rs, row) -> new Rule(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getInt(6), rs.getInt(7)), runId, candidate.revisionId());
        if (rows.isEmpty()) return;
        if (owner.state().terminal() || (reading != null && FlinkPressureSample.TERMINAL_STATES.contains(reading.jobState()))) {
            stop(runId, "ENDED", now);
            return;
        }
        var rule = rows.get(0);
        long elapsed = sampledAtMs - rule.sampledAtMs();
        if (rule.sampledAtMs() > 0 && elapsed < MIN_INTERVAL_MS) return;
        Long value = reading == null ? null : reading.lag();
        int high = value != null && value >= rule.highThreshold()
                ? Math.min((elapsed > MAX_INTERVAL_MS ? 0 : rule.highStreak()) + 1, 3) : 0;
        int low = value != null && value <= rule.lowThreshold()
                ? Math.min((elapsed > MAX_INTERVAL_MS ? 0 : rule.lowStreak()) + 1, 2) : 0;
        var active = jdbc.queryForList("SELECT id FROM flink_kafka_alert WHERE run_id = ? AND status = 'OPEN'",
                String.class, runId);
        if (active.isEmpty() && high == 3) {
            String alert = IdWorker.getIdStr();
            jdbc.update("""
                    INSERT INTO flink_kafka_alert (id, run_id, vertex_id, vertex_name, peak_lag, status, opened_at)
                    VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
                    """, alert, runId, rule.vertex(), rule.vertexName(), value, now);
            outbox.append(new FlinkAlertEvent(alert, runId, owner.operator(), FlinkAlertEvent.Type.KAFKA_OPEN), now);
        } else if (!active.isEmpty() && value != null) {
            String alert = active.get(0);
            if (low == 2) {
                close(runId, "RECOVERED", now);
                outbox.append(new FlinkAlertEvent(alert, runId, owner.operator(), FlinkAlertEvent.Type.KAFKA_CLEAR), now);
            } else {
                jdbc.update("UPDATE flink_kafka_alert SET peak_lag = ? WHERE id = ? AND peak_lag < ?", value, alert, value);
            }
        }
        jdbc.update("""
                UPDATE flink_kafka_watch SET sampled_at_ms = ?, last_lag = ?, high_streak = ?, low_streak = ? WHERE run_id = ?
                """, sampledAtMs, value, high, low, runId);
    }

    private Owner lockRun(String runId, String cluster, String jobId) {
        var rows = jdbc.query("""
                SELECT state, operator_id FROM flink_deployment
                WHERE id = ? AND cluster_key = ? AND engine_job_id = ? FOR UPDATE
                """, (rs, row) -> new Owner(State.valueOf(rs.getString(1)), rs.getString(2)), runId, cluster, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void stop(String runId, String status, LocalDateTime now) {
        close(runId, status, now);
        jdbc.update("UPDATE flink_kafka_watch SET enabled = FALSE WHERE run_id = ?", runId);
    }

    private void close(String runId, String status, LocalDateTime now) {
        jdbc.update("UPDATE flink_kafka_alert SET status = ?, closed_at = ? WHERE run_id = ? AND status = 'OPEN'",
                status, now, runId);
    }

    private record Owner(State state, String operator) {}
    private record Rule(String vertex, String vertexName, long highThreshold, long lowThreshold, long sampledAtMs,
                        int highStreak, int lowStreak) {}
    record Candidate(String id, String project, String jobId, State state, String revisionId, String vertexId) {}
    public record History(boolean available, Watch watch, List<Alert> alerts) {}
    public record Watch(String vertexId, String vertexName, long highThreshold, long lowThreshold, boolean enabled,
                        Long lastLag, long sampledAtMs) {}
    public record Alert(String id, String vertexId, String vertexName, long peakLag, String status,
                        LocalDateTime openedAt, LocalDateTime closedAt) {}
}
