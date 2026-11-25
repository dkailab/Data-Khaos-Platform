package com.datakhaos.workflow.api.model;

/** Durable Flink alert transition delivered to the notification service. */
public record FlinkAlertEvent(String alertId, String runId, String receiverId, Type type) {
    public static final String NOTIFICATION_PATH = "/internal/notification/flink-alert-event";
    public static final String TOKEN_HEADER = "X-Internal-Notification-Token";

    public enum Type { OPEN, RECOVERED, PRESSURE_OPEN, PRESSURE_CLEAR, KAFKA_OPEN, KAFKA_CLEAR, WMARK_OPEN, WMARK_CLEAR }
}
