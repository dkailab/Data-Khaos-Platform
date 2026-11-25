package com.datakhaos.workflow.runner;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
class FlinkAlertOutbox {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    FlinkAlertOutbox(JdbcTemplate jdbc, @Value("${FLINK_ALERT_NOTIFICATION_TOKEN:}") String token) {
        this.jdbc = jdbc;
        this.enabled = !token.isBlank();
    }

    boolean enabled() {
        return enabled;
    }

    void append(FlinkAlertEvent event, LocalDateTime now) {
        if (!enabled) return;
        jdbc.update("""
                INSERT INTO flink_alert_outbox (id, alert_id, run_id, receiver_id, event_type, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, IdWorker.getIdStr(), event.alertId(), event.runId(), event.receiverId(), event.type().name(), now);
    }

    List<Pending> due(LocalDateTime now) {
        return jdbc.query("""
                SELECT id, alert_id, run_id, receiver_id, event_type, attempt_count
                FROM flink_alert_outbox e
                WHERE delivered_at IS NULL AND next_attempt_at <= ?
                  AND (event_type IN ('OPEN', 'PRESSURE_OPEN') OR NOT EXISTS (
                    SELECT 1 FROM flink_alert_outbox prior
                    WHERE prior.alert_id = e.alert_id AND prior.event_type IN ('OPEN', 'PRESSURE_OPEN') AND prior.delivered_at IS NULL))
                ORDER BY next_attempt_at, id LIMIT 20
                """, (rs, row) -> new Pending(rs.getString("id"), rs.getInt("attempt_count"),
                new FlinkAlertEvent(rs.getString("alert_id"), rs.getString("run_id"), rs.getString("receiver_id"),
                        FlinkAlertEvent.Type.valueOf(rs.getString("event_type")))), now);
    }

    void delivered(Pending pending, LocalDateTime now) {
        jdbc.update("""
                UPDATE flink_alert_outbox SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL
                """, now, pending.id());
    }

    void retry(Pending pending, LocalDateTime now) {
        int failures = pending.attempts();
        long delay = Math.min(300, 10L << Math.min(failures, 5));
        jdbc.update("""
                UPDATE flink_alert_outbox SET next_attempt_at = ?, attempt_count = attempt_count + 1
                WHERE id = ? AND delivered_at IS NULL AND attempt_count = ?
                """, now.plusSeconds(delay), pending.id(), failures);
    }

    record Pending(String id, int attempts, FlinkAlertEvent event) {}
}
