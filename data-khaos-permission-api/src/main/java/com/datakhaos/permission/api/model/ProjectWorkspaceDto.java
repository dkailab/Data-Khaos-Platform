package com.datakhaos.permission.api.model;

import java.util.List;

public record ProjectWorkspaceDto(String projectGroupId, String name, boolean primary,
                                  String roleCode, List<String> capabilities) {
    public static ProjectWorkspaceDto of(ProjectGroupDto group) {
        return new ProjectWorkspaceDto(group.getId(), group.getProjectName(), Boolean.TRUE.equals(group.getPrimary()),
                group.getRoleCode(), List.copyOf(group.getCapabilityFlags()));
    }
}
