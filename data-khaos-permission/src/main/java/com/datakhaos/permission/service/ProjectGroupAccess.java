package com.datakhaos.permission.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.permission.api.model.ProjectGroupDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Objects;

@Component
public class ProjectGroupAccess {
    private final HttpServletRequest request;
    private final ProjectGroupService groups;
    private final PermissionService permissions;
    private final String jwtSecret;

    public ProjectGroupAccess(HttpServletRequest request, ProjectGroupService groups, PermissionService permissions,
                              @Value("${data-khaos.jwt.secret}") String jwtSecret) {
        this.request = request;
        this.groups = groups;
        this.permissions = permissions;
        this.jwtSecret = jwtSecret;
    }

    public String actorId() {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String token = authorization.substring("Bearer ".length());
        if (!JwtUtil.verify(token, jwtSecret)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Date expiry = JwtUtil.getExpiresAt(token);
        String id = JwtUtil.getUid(token, jwtSecret);
        if (expiry == null || !expiry.after(new Date()) || id == null || id.isBlank() || !groups.isActiveUser(id)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    public boolean isPlatformAdmin(String actorId) {
        return permissions.isPlatformAdministrator(actorId);
    }

    public void requirePlatformAdmin() {
        if (!isPlatformAdmin(actorId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public void requireSelfOrAdmin(String userId) {
        String actor = actorId();
        if (!actor.equals(userId) && !isPlatformAdmin(actor)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public ProjectGroupDto requireMembership(String projectId) {
        return groups.getUserProjectGroups(actorId()).stream()
                .filter(group -> Objects.equals(group.getId(), projectId)).findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "项目不存在或不可访问"));
    }

    public void requireManagement(String projectId) {
        String actor = actorId();
        if (isPlatformAdmin(actor)) {
            return;
        }
        ProjectGroupDto membership = requireMembership(projectId);
        if (!membership.getCapabilityFlags().contains("pg:manage")) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public void requireRead(String projectId) {
        if (!isPlatformAdmin(actorId())) {
            requireMembership(projectId);
        }
    }
}
