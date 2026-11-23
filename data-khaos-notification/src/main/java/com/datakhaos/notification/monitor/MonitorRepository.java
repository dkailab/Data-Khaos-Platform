package com.datakhaos.notification.monitor;

import com.datakhaos.notification.monitor.MonitorViews.Alert;
import com.datakhaos.notification.monitor.MonitorViews.ServiceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
class MonitorRepository {
    private static final String ALERT_COLUMNS = """
            id, service_id, service_name, opened_at, acknowledged_at, acknowledged_by, recovered_at, reason
            """;
    private static final RowMapper<Alert> ALERT_MAPPER = (rs, row) -> new Alert(rs.getString("id"),
            rs.getString("service_id"), rs.getString("service_name"), time(rs, "opened_at"),
            time(rs, "acknowledged_at"), rs.getString("acknowledged_by"), time(rs, "recovered_at"),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    MonitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ServiceStatus> services() {
        return jdbc.query("""
                SELECT service_id, service_name, status, registered_count, reachable_count, checked_at,
                       reason, active_alert_id
                FROM platform_service_monitor ORDER BY service_id
                """, (rs, row) -> new ServiceStatus(rs.getString("service_id"), rs.getString("service_name"),
                MonitorStatus.valueOf(rs.getString("status")), rs.getInt("registered_count"),
                rs.getInt("reachable_count"), time(rs, "checked_at"), rs.getString("reason"),
                rs.getString("active_alert_id")));
    }

    State lockState(String serviceId) {
        return jdbc.queryForObject("""
                SELECT service_name, sample_bucket, healthy_streak, unhealthy_streak, active_alert_id
                FROM platform_service_monitor WHERE service_id = ? FOR UPDATE
                """, (rs, row) -> new State(rs.getString("service_name"), rs.getLong("sample_bucket"),
                rs.getInt("healthy_streak"), rs.getInt("unhealthy_streak"), rs.getString("active_alert_id")), serviceId);
    }

    void saveSample(String serviceId, long bucket, LocalDateTime now, MonitorProbe.Sample sample,
                    int healthy, int unhealthy, String activeAlert) {
        jdbc.update("""
                UPDATE platform_service_monitor
                SET status = ?, registered_count = ?, reachable_count = ?, reason = ?, sample_bucket = ?,
                    checked_at = ?, healthy_streak = ?, unhealthy_streak = ?, active_alert_id = ?
                WHERE service_id = ?
                """, sample.status().name(), sample.registeredCount(), sample.reachableCount(), sample.reason(),
                bucket, now, healthy, unhealthy, activeAlert, serviceId);
    }

    void open(String id, String serviceId, String serviceName, LocalDateTime now, String reason) {
        jdbc.update("""
                INSERT INTO platform_monitor_alert (id, service_id, service_name, opened_at, reason)
                VALUES (?, ?, ?, ?, ?)
                """, id, serviceId, serviceName, now, reason);
    }

    void recover(String id, LocalDateTime now) {
        jdbc.update("UPDATE platform_monitor_alert SET recovered_at = ? WHERE id = ? AND recovered_at IS NULL",
                now, id);
    }

    long countAlerts(boolean openOnly) {
        String filter = openOnly ? " WHERE recovered_at IS NULL" : "";
        return jdbc.queryForObject("SELECT COUNT(*) FROM platform_monitor_alert" + filter, Long.class);
    }

    List<Alert> alerts(boolean openOnly, long size, long offset) {
        String filter = openOnly ? " WHERE recovered_at IS NULL" : "";
        return jdbc.query("SELECT " + ALERT_COLUMNS + " FROM platform_monitor_alert" + filter
                + " ORDER BY opened_at DESC, id DESC LIMIT ? OFFSET ?", ALERT_MAPPER, size, offset);
    }

    void acknowledge(String id, String userId, LocalDateTime now) {
        jdbc.update("""
                UPDATE platform_monitor_alert SET acknowledged_at = ?, acknowledged_by = ?
                WHERE id = ? AND acknowledged_at IS NULL
                """, now, userId, id);
    }

    Alert findAlert(String id) {
        return jdbc.query("SELECT " + ALERT_COLUMNS + " FROM platform_monitor_alert WHERE id = ?",
                ALERT_MAPPER, id).stream().findFirst().orElse(null);
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    record State(String name, long bucket, int healthy, int unhealthy, String activeAlert) {}
}
