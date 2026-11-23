package com.datakhaos.permission.api.model;

public record ProjectWorkspaceResourceDto(String id, String resourceType, String resourceId, String name,
                                          Boolean available) {}
