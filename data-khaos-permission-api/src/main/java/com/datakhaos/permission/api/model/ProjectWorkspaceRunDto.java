package com.datakhaos.permission.api.model;

import java.time.LocalDateTime;

public record ProjectWorkspaceRunDto(String id, String kind, String taskId, String taskName,
                                     String status, String bizDate, LocalDateTime startedAt,
                                     Long durationMs, String triggerType, LocalDateTime alertAcknowledgedAt) {}
