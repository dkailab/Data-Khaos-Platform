package com.datakhaos.notification.monitor;

public enum MonitorStatus {
    UP, DEGRADED, DOWN, UNKNOWN;

    boolean unhealthy() {
        return this == DEGRADED || this == DOWN;
    }
}
