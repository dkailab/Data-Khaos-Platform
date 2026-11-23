package com.datakhaos.permission.api.model;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectWorkspaceRunDetailDto(String id, String kind, String taskId, String taskName,
                                           String status, String bizDate, LocalDateTime startedAt,
                                           LocalDateTime endedAt, Long durationMs, String triggerType, String failureReason,
                                           String recoveryCode, LocalDateTime recoveryResolvedAt,
                                           String recoveryResolvedBy, String recoveryNote, LocalDateTime alertAcknowledgedAt,
                                           List<Node> nodes) {
    public record Node(String code, String name, String type, String status, Long durationMs,
                       LocalDateTime startedAt, LocalDateTime endedAt, String refType, String refId) {}
}
