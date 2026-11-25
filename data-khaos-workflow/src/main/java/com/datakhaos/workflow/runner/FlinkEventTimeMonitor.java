package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.workflow.entity.FlinkDeployment.State;
import com.datakhaos.workflow.flink.FlinkEventTimeDiagnosis;
import com.datakhaos.workflow.flink.FlinkEventTimeSample;
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
public class FlinkEventTimeMonitor {
    static final int BATCH_SIZE = 12;
    private static final int POINT_LIMIT = 120;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FlinkAlertOutbox outbox;
    private final boolean enabled;

    public FlinkEventTimeMonitor(JdbcTemplate jdbc, ObjectMapper json, FlinkAlertOutbox outbox,
                                 @Value("${FLINK_EVENT_TIME_ALERT_ENABLED:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) throw new BusinessException(ResultCode.FORBIDDEN, "水位线巡检未启用");
    }

    public History history(String runId) {
        if (!enabled) return new History(false, null, List.of(), List.of());
        var watches = jdbc.query("SELECT vertex_id, enabled FROM flink_event_time_watch WHERE run_id = ?",
                (rs, row) -> new Watch(rs.getString(1), rs.getBoolean(2)), runId);
        var points = jdbc.query("""
                SELECT vertex_id, sampled_at_ms, watermark_ms, input_delta, diagnosis
                FROM flink_event_time_point WHERE run_id = ? ORDER BY sampled_at_ms DESC LIMIT 120
                """, (rs, row) -> new Point(rs.getString(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getObject(4, Long.class), rs.getString(5)), runId);
        var alerts = jdbc.query("""
                SELECT id, vertex_id, stalled_watermark_ms, status, opened_at, closed_at
                FROM flink_event_time_alert WHERE run_id = ? ORDER BY opened_at DESC, id DESC LIMIT 30
                """, (rs, row) -> new Alert(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class)), runId);
        return new History(true, watches.isEmpty() ? null : watches.get(0), points, alerts);
    }

    @Transactional
    public void configure(String runId, String cluster, String jobId, String vertexId, boolean active, LocalDateTime now) {
        requireEnabled();
        var owner = lockRun(runId, cluster, jobId);
        if (owner == null) throw new BusinessException(409, "运行绑定已变化");
        if (!active) {
            stop(runId, "DISABLED", now);
            return;
        }
        if (owner.state() != State.SUBMITTED) throw new BusinessException(409, "仅已提交的运行可以启用水位线巡检");
        var current = jdbc.queryForList("SELECT enabled FROM flink_event_time_watch WHERE run_id = ?", Boolean.class, runId);
        if (!current.isEmpty() && Boolean.TRUE.equals(current.get(0))) {
            throw new BusinessException(409, "此运行已有水位线巡检，请先停用");
        }
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO flink_event_time_watch (revision_id, vertex_id, enabled, run_id) VALUES (?, ?, TRUE, ?)
                    """, IdWorker.getIdStr(), vertexId, runId);
        } else {
            jdbc.update("""
                    UPDATE flink_event_time_watch SET revision_id = ?, vertex_id = ?, enabled = TRUE, sampled_at_ms = 0,
                    baseline_json = NULL, stalled_intervals = 0 WHERE run_id = ?
                    """, IdWorker.getIdStr(), vertexId, runId);
        }
    }

    List<Candidate> candidates(String cluster, String after) {
        return jdbc.query("""
                SELECT r.id, r.project_id, r.engine_job_id, r.state, w.revision_id, w.vertex_id
                FROM flink_event_time_watch w JOIN flink_deployment r ON r.id = w.run_id
                WHERE w.enabled = TRUE AND r.cluster_key = ? AND w.run_id > ? AND r.engine_job_id IS NOT NULL
                ORDER BY w.run_id LIMIT ?
                """, (rs, row) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3),
                State.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6)), cluster, after, BATCH_SIZE);
    }

    @Transactional
    public void record(Candidate candidate, String cluster, FlinkEventTimeSample sample, long sampledAtMs, LocalDateTime now) {
        String runId = candidate.id();
        var owner = lockRun(runId, cluster, candidate.jobId());
        if (owner == null) return;
        var rows = jdbc.query("""
                SELECT sampled_at_ms, baseline_json, stalled_intervals, next_slot
                FROM flink_event_time_watch WHERE run_id = ? AND revision_id = ? AND enabled = TRUE
                """, (rs, row) -> new Baseline(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                runId, candidate.revisionId());
        if (rows.isEmpty()) return;
        if (owner.state().terminal() || (sample != null && FlinkPressureSample.TERMINAL_STATES.contains(sample.status()))) {
            stop(runId, "ENDED", now);
            return;
        }
        var previous = rows.get(0);
        if (previous.sampledAtMs() > 0 && sampledAtMs - previous.sampledAtMs() < 20_000) return;
        var diagnostic = FlinkEventTimeDiagnosis.compare(decode(previous.json()), sample, previous.streak());
        Long watermark = sample == null || sample.inputWatermark() == null ? null : sample.inputWatermark().toEpochMilli();

        jdbc.update("DELETE FROM flink_event_time_point WHERE run_id = ? AND slot = ?", runId, previous.nextSlot());
        jdbc.update("""
                INSERT INTO flink_event_time_point
                    (run_id, slot, vertex_id, sampled_at_ms, watermark_ms, input_delta, diagnosis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, previous.nextSlot(), candidate.vertexId(), sampledAtMs, watermark,
                diagnostic.inputDelta(), diagnostic.state().name());
        updateAlert(candidate, owner, diagnostic.state(), watermark, now);
        jdbc.update("""
                UPDATE flink_event_time_watch SET sampled_at_ms = ?, baseline_json = ?, stalled_intervals = ?, next_slot = ?
                WHERE run_id = ?
                """, sampledAtMs, FlinkEventTimeDiagnosis.usable(sample) ? encode(sample) : null,
                diagnostic.stalledIntervals(), (previous.nextSlot() + 1) % POINT_LIMIT, runId);
    }

    private void updateAlert(Candidate candidate, Owner owner, FlinkEventTimeDiagnosis.State state,
                             Long watermark, LocalDateTime now) {
        if (state != FlinkEventTimeDiagnosis.State.STALLED && state != FlinkEventTimeDiagnosis.State.PROGRESSING) return;
        String runId = candidate.id();
        var open = jdbc.query("""
                SELECT id, stalled_watermark_ms FROM flink_event_time_alert WHERE run_id = ? AND status = 'OPEN'
                """, (rs, row) -> new OpenAlert(rs.getString(1), rs.getLong(2)), runId);
        if (open.isEmpty() && state == FlinkEventTimeDiagnosis.State.STALLED) {
            String alert = IdWorker.getIdStr();
            jdbc.update("""
                    INSERT INTO flink_event_time_alert (id, run_id, vertex_id, stalled_watermark_ms, status, opened_at)
                    VALUES (?, ?, ?, ?, 'OPEN', ?)
                    """, alert, runId, candidate.vertexId(), watermark, now);
            outbox.append(new FlinkAlertEvent(alert, runId, owner.operator(), FlinkAlertEvent.Type.WMARK_OPEN), now);
        } else if (!open.isEmpty() && state == FlinkEventTimeDiagnosis.State.PROGRESSING && watermark > open.get(0).watermark()) {
            String alert = open.get(0).id();
            jdbc.update("UPDATE flink_event_time_alert SET status = 'RECOVERED', closed_at = ? WHERE id = ?", now, alert);
            outbox.append(new FlinkAlertEvent(alert, runId, owner.operator(), FlinkAlertEvent.Type.WMARK_CLEAR), now);
        }
    }

    private Owner lockRun(String id, String cluster, String jobId) {
        var rows = jdbc.query("""
                SELECT state, operator_id FROM flink_deployment
                WHERE id = ? AND cluster_key = ? AND engine_job_id = ? FOR UPDATE
                """, (rs, row) -> new Owner(State.valueOf(rs.getString(1)), rs.getString(2)), id, cluster, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void stop(String runId, String status, LocalDateTime now) {
        jdbc.update("UPDATE flink_event_time_alert SET status = ?, closed_at = ? WHERE run_id = ? AND status = 'OPEN'",
                status, now, runId);
        jdbc.update("UPDATE flink_event_time_watch SET enabled = FALSE WHERE run_id = ?", runId);
    }

    private FlinkEventTimeSample decode(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, FlinkEventTimeSample.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取水位线巡检基线", e);
        }
    }

    private String encode(FlinkEventTimeSample value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存水位线巡检基线", e);
        }
    }

    private record Owner(State state, String operator) {}
    private record Baseline(long sampledAtMs, String json, int streak, int nextSlot) {}
    private record OpenAlert(String id, long watermark) {}
    record Candidate(String id, String project, String jobId, State state, String revisionId, String vertexId) {}
    public record Watch(String vertexId, boolean enabled) {}
    public record Point(String vertexId, long sampledAtMs, Long watermarkMs, Long inputDelta, String diagnosis) {}
    public record Alert(String id, String vertexId, long stalledWatermarkMs, String status,
                        LocalDateTime openedAt, LocalDateTime closedAt) {}
    public record History(boolean available, Watch watch, List<Point> points, List<Alert> alerts) {}
}
