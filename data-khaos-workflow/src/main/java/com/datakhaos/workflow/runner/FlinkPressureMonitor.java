package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkPressureSample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FlinkPressureMonitor {
    static final int BATCH_SIZE = 20;
    static final long MIN_INTERVAL_MS = 20_000;
    private static final long MAX_INTERVAL_MS = 120_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FlinkAlertOutbox outbox;
    private final boolean enabled;

    public FlinkPressureMonitor(JdbcTemplate jdbc, ObjectMapper json, FlinkAlertOutbox outbox,
                                @Value("${FLINK_PRESSURE_ALERT_ENABLED:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    List<Run> candidates(String clusterKey, String after) {
        return jdbc.query("""
                SELECT id, project_id, engine_job_id, state FROM flink_deployment r
                WHERE cluster_key = ? AND id > ? AND engine_job_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM flink_pressure_watch w WHERE w.run_id = r.id AND w.ended = TRUE)
                  AND (state IN ('SUBMITTED', 'CANCEL_REQUESTED', 'STOP_REQUESTED') OR EXISTS (
                    SELECT 1 FROM flink_pressure_alert a WHERE a.run_id = r.id AND a.status = 'OPEN'))
                ORDER BY id LIMIT ?
                """, (rs, row) -> new Run(rs.getString(1), rs.getString(2), rs.getString(3),
                State.valueOf(rs.getString(4))), clusterKey, after, BATCH_SIZE);
    }

    public History history(String runId) {
        if (!enabled) return new History(false, List.of());
        return new History(true, jdbc.query("""
                SELECT id, vertex_id, peak_percent, status, opened_at, closed_at
                FROM flink_pressure_alert WHERE run_id = ?
                ORDER BY opened_at DESC, id DESC LIMIT 30
                """, (rs, row) -> new Alert(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getString(4),
                rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class)), runId));
    }

    @Transactional
    public void record(String runId, String clusterKey, String jobId, FlinkPressureSample sample, LocalDateTime now) {
        var rows = jdbc.query("""
                SELECT state, operator_id FROM flink_deployment
                WHERE id = ? AND cluster_key = ? AND engine_job_id = ? FOR UPDATE
                """, (rs, row) -> new Owner(State.valueOf(rs.getString(1)), rs.getString(2)), runId, clusterKey, jobId);
        if (rows.isEmpty()) return;
        var owner = rows.get(0);
        var watches = jdbc.query("""
                SELECT sampled_at_ms, sample_json, high_streak, low_streak, ended FROM flink_pressure_watch WHERE run_id = ?
                """, (rs, row) -> new Watch(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                rs.getBoolean(5)), runId);
        var watch = watches.isEmpty() ? null : watches.get(0);
        if (watch != null && watch.ended()) return;
        if (owner.state().terminal() || (sample != null && FlinkPressureSample.TERMINAL_STATES.contains(sample.jobState()))) {
            jdbc.update("UPDATE flink_pressure_alert SET status = 'ENDED', closed_at = ? WHERE run_id = ? AND status = 'OPEN'",
                    now, runId);
            save(runId, watch == null, new Watch(0, "{}", 0, 0, true));
            return;
        }
        if (sample == null) return;
        long elapsed = watch == null ? 0 : sample.sampledAtMs() - watch.atMs();
        if (watch != null && elapsed < MIN_INTERVAL_MS) return;

        Reading reading = watch == null || elapsed > MAX_INTERVAL_MS ? new Reading(null, 0, false)
                : measure(read(watch.sample()), sample, elapsed);
        int high = reading.peak() >= 50 ? Math.min(watch.high() + 1, 3) : 0;
        int low = reading.complete() && reading.peak() <= 20 ? Math.min(watch.low() + 1, 2) : 0;
        var active = jdbc.queryForList("SELECT id FROM flink_pressure_alert WHERE run_id = ? AND status = 'OPEN'",
                String.class, runId);
        if (active.isEmpty() && high == 3) {
            String id = IdWorker.getIdStr();
            jdbc.update("""
                    INSERT INTO flink_pressure_alert (id, run_id, vertex_id, peak_percent, status, opened_at)
                    VALUES (?, ?, ?, ?, 'OPEN', ?)
                    """, id, runId, reading.vertex(), reading.peak(), now);
            outbox.append(new FlinkAlertEvent(id, runId, owner.operatorId(), FlinkAlertEvent.Type.PRESSURE_OPEN), now);
        } else if (!active.isEmpty()) {
            String id = active.get(0);
            if (low == 2) {
                jdbc.update("UPDATE flink_pressure_alert SET status = 'RECOVERED', closed_at = ? WHERE id = ?", now, id);
                outbox.append(new FlinkAlertEvent(id, runId, owner.operatorId(), FlinkAlertEvent.Type.PRESSURE_CLEAR), now);
            } else if (reading.vertex() != null) {
                jdbc.update("UPDATE flink_pressure_alert SET vertex_id = ?, peak_percent = ? WHERE id = ? AND peak_percent < ?",
                        reading.vertex(), reading.peak(), id, reading.peak());
            }
        }
        save(runId, watch == null, new Watch(sample.sampledAtMs(), write(sample), high, low, false));
    }

    private Reading measure(FlinkPressureSample prior, FlinkPressureSample sample, long elapsed) {
        if (!"RUNNING".equals(sample.jobState()) || !"RUNNING".equals(prior.jobState())) return new Reading(null, 0, false);
        String vertex = null;
        double peak = 0;
        boolean complete = !sample.counters().isEmpty();
        for (var entry : sample.counters().entrySet()) {
            var current = entry.getValue();
            var old = prior.counters().get(entry.getKey());
            if (old == null || !current.usable() || !old.usable() || old.startedAt() != current.startedAt()
                    || old.parallelism() != current.parallelism() || current.totalMs() < old.totalMs()) {
                complete = false;
                continue;
            }
            double percent = Math.min(100, 100.0 * (current.totalMs() - old.totalMs()) / elapsed / current.parallelism());
            if (vertex == null || percent > peak || (percent == peak && entry.getKey().compareTo(vertex) < 0)) {
                vertex = entry.getKey();
                peak = percent;
            }
        }
        return new Reading(vertex, peak, complete);
    }

    private void save(String runId, boolean create, Watch watch) {
        if (create) {
            jdbc.update("""
                    INSERT INTO flink_pressure_watch (sampled_at_ms, sample_json, high_streak, low_streak, ended, run_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, watch.atMs(), watch.sample(), watch.high(), watch.low(), watch.ended(), runId);
        } else {
            jdbc.update("""
                    UPDATE flink_pressure_watch SET sampled_at_ms = ?, sample_json = ?, high_streak = ?, low_streak = ?,
                    ended = ? WHERE run_id = ?
                    """, watch.atMs(), watch.sample(), watch.high(), watch.low(), watch.ended(), runId);
        }
    }

    private FlinkPressureSample read(String value) {
        try {
            return json.readValue(value, FlinkPressureSample.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取 Flink 反压采样", e);
        }
    }

    private String write(FlinkPressureSample value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存 Flink 反压采样", e);
        }
    }

    private record Owner(State state, String operatorId) {}
    private record Watch(long atMs, String sample, int high, int low, boolean ended) {}
    private record Reading(String vertex, double peak, boolean complete) {}
    record Run(String id, String projectId, String jobId, State state) {}
    public record History(boolean enabled, List<Alert> alerts) {}
    public record Alert(String id, String vertexId, double peakPercent, String status,
                        LocalDateTime openedAt, LocalDateTime closedAt) {}
}
