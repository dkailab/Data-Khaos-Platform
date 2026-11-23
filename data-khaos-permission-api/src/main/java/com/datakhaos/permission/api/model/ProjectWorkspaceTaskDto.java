package com.datakhaos.permission.api.model;

import java.time.LocalDateTime;

public record ProjectWorkspaceTaskDto(String id, String kind, String name, String status,
                                      LocalDateTime updatedAt) {}
