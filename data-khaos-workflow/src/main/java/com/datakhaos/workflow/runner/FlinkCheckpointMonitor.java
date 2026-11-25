package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.flink.FlinkCheckpointView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

@Service
public class FlinkCheckpointMonitor {
    private static final int TRIGGER_COUNT = 3;
    private static final RowMapper<Alert> ALERT = (rs, row) -> new Alert(rs.getString("id"),
            rs.getString("status"), rs.getLong("first_failed_checkpoint_id"), rs.getLong("last_failed_checkpoint_id"),
            rs.getObject("opened_at", LocalDateTime.class), rs.getObject("closed_at", LocalDateTime.class),
            rs.getObject("recovery_checkpoint_id", Long.class));

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final FlinkAlertOutbox outbox;

    public FlinkCheckpointMonitor(JdbcTemplate jdbc, FlinkAlertOutbox outbox, @Value("${FLINK_CHECKPOINT_ALERT_ENABLED:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    List<Run> candidates(String clusterKey, String after) {
        return jdbc.query("""
                SELECT id, project_id, state, engine_job_id FROM flink_deployment r
                WHERE cluster_key = ? AND id > ? AND engine_job_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM flink_checkpoint_watch w WHERE w.run_id = r.id AND w.ended = TRUE)
                  AND (state IN ('SUBMITTED', 'CANCEL_REQUESTED', 'STOP_REQUESTED') OR EXISTS (
                    SELECT 1 FROM flink_checkpoint_alert a WHERE a.run_id = r.id AND a.status = 'OPEN'))
                ORDER BY id LIMIT 20
                """, (rs, row) -> new Run(rs.getString("id"), rs.getString("project_id"), rs.getString("engine_job_id"),
                State.valueOf(rs.getString("state"))), clusterKey, after);
    }

    public AlertHistory history(String runId) {
        if (!enabled) return new AlertHistory(false, List.of());
        var alerts = jdbc.query("""
                SELECT * FROM flink_checkpoint_alert WHERE run_id = ?
                ORDER BY opened_at DESC, id DESC LIMIT 30
                """, ALERT, runId);
        return new AlertHistory(true, alerts);
    }

    @Transactional
    public void record(String runId, String clusterKey, String jobId, FlinkCheckpointView sample,
                       boolean engineEnded, LocalDateTime observedAt) {
        // Lock the existing run before creating or updating its watch and alert rows.
        var runs = jdbc.query("""
                SELECT state, operator_id FROM flink_deployment WHERE id = ? AND cluster_key = ? AND engine_job_id = ? FOR UPDATE
                """, (rs, row) -> new RunOwner(State.valueOf(rs.getString(1)), rs.getString(2)), runId, clusterKey, jobId);
        if (runs.isEmpty()) return;
        var owner = runs.get(0);
        var watches = jdbc.query("""
                SELECT last_checkpoint_id, ended FROM flink_checkpoint_watch WHERE run_id = ?
                """, (rs, row) -> new Watch(rs.getLong(1), rs.getBoolean(2)), runId);
        var watch = watches.isEmpty() ? new Watch(-1, false) : watches.get(0);
        if (watch.ended()) return;

        if (engineEnded || owner.state().terminal()) {
            jdbc.update("""
                    UPDATE flink_checkpoint_alert SET status = 'ENDED', closed_at = ?
                    WHERE run_id = ? AND status = 'OPEN'
                    """, observedAt, runId);
            saveWatch(runId, watches.isEmpty(), watch.lastCheckpointId(), true);
            return;
        }
        var recent = settled(sample);
        if (recent.isEmpty()) return;
        var newest = recent.get(0);
        if (newest.id() <= watch.lastCheckpointId()) return;

        var completed = recent.stream().filter(entry -> "COMPLETED".equals(entry.status()))
                .findFirst().orElse(null);
        if (completed != null && completed.id() > watch.lastCheckpointId()) {
            var open = jdbc.queryForList("""
                    SELECT id FROM flink_checkpoint_alert WHERE run_id = ? AND status = 'OPEN'
                    """, String.class, runId);
            if (!open.isEmpty()) {
                String alertId = open.get(0);
                jdbc.update("""
                        UPDATE flink_checkpoint_alert SET status = 'RECOVERED', closed_at = ?, recovery_checkpoint_id = ?
                        WHERE id = ?
                        """, observedAt, completed.id(), alertId);
                outbox.append(new FlinkAlertEvent(alertId, runId, owner.operatorId(),
                        FlinkAlertEvent.Type.RECOVERED), observedAt);
            }
        }
        if ("FAILED".equals(newest.status())) {
            int updated = jdbc.update("""
                    UPDATE flink_checkpoint_alert SET last_failed_checkpoint_id = ?
                    WHERE run_id = ? AND status = 'OPEN'
                    """, newest.id(), runId);
            var streak = recent.stream().limit(TRIGGER_COUNT).toList();
            if (updated == 0 && streak.size() == TRIGGER_COUNT && streak.stream().allMatch(e -> "FAILED".equals(e.status()))) {
                String alertId = IdWorker.getIdStr();
                jdbc.update("""
                        INSERT INTO flink_checkpoint_alert
                          (id, run_id, first_failed_checkpoint_id, last_failed_checkpoint_id, status, opened_at)
                        VALUES (?, ?, ?, ?, 'OPEN', ?)
                        """, alertId, runId, streak.get(TRIGGER_COUNT - 1).id(), newest.id(), observedAt);
                outbox.append(new FlinkAlertEvent(alertId, runId, owner.operatorId(),
                        FlinkAlertEvent.Type.OPEN), observedAt);
            }
        }
        saveWatch(runId, watches.isEmpty(), newest.id(), false);
    }

    private void saveWatch(String runId, boolean create, long checkpointId, boolean ended) {
        if (create) {
            jdbc.update("INSERT INTO flink_checkpoint_watch (run_id, last_checkpoint_id, ended) VALUES (?, ?, ?)",
                    runId, checkpointId, ended);
        } else {
            jdbc.update("UPDATE flink_checkpoint_watch SET last_checkpoint_id = ?, ended = ? WHERE run_id = ?",
                    checkpointId, ended, runId);
        }
    }

    private List<FlinkCheckpointView.Entry> settled(FlinkCheckpointView sample) {
        if (sample == null || !sample.available()) return List.of();
        var byId = new TreeMap<Long, FlinkCheckpointView.Entry>(Comparator.reverseOrder());
        Stream.concat(sample.recent().stream(), Stream.of(sample.latestCompleted(), sample.latestFailed()))
                .filter(entry -> entry != null && !entry.savepoint() && !"IN_PROGRESS".equals(entry.status()))
                .forEach(entry -> byId.putIfAbsent(entry.id(), entry));
        return List.copyOf(byId.values());
    }

    private record RunOwner(State state, String operatorId) {}
    private record Watch(long lastCheckpointId, boolean ended) {}
    record Run(String id, String projectId, String jobId, State state) {}
    public record AlertHistory(boolean enabled, List<Alert> alerts) {}
    public record Alert(String id, String status, long firstFailedCheckpointId, long lastFailedCheckpointId,
                        LocalDateTime openedAt, LocalDateTime closedAt, Long recoveryCheckpointId) {}
}
