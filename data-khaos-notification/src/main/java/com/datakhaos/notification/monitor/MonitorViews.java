package com.datakhaos.notification.monitor;

import java.time.LocalDateTime;

public final class MonitorViews {
    private MonitorViews() {}

    public record ServiceStatus(String serviceId, String serviceName, MonitorStatus status, int registeredCount,
                                int reachableCount, LocalDateTime checkedAt, String reason, String activeAlertId) {}

    public record Alert(String id, String serviceId, String serviceName, LocalDateTime openedAt,
                        LocalDateTime acknowledgedAt, String acknowledgedBy, LocalDateTime recoveredAt,
                        String reason) {}
}
