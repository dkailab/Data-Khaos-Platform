package com.datakhaos.permission.api.model;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectWorkspaceTaskDetailDto(String id, String kind, String name, String status,
                                            LocalDateTime updatedAt, String engine, String sourceName,
                                            String targetName, boolean workspaceRunnable, List<Node> nodes) {
    public record Node(String code, String name, String type) {}
}
