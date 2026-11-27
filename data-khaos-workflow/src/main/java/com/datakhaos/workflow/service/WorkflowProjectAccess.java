package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.permission.api.model.ProjectWorkspaceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

/** Permission validates the bearer token and resolves the role in the workflow's project. */
@Component
public class WorkflowProjectAccess {
    private static final String WORKSPACE =
            "http://data-khaos-permission/api/permission/project-group/{projectId}/workspace";

    private final RestTemplate http;
    private final ObjectProvider<HttpServletRequest> requests;

    public WorkflowProjectAccess(@Qualifier("orchestrationRestTemplate") RestTemplate http,
                                 ObjectProvider<HttpServletRequest> requests) {
        this.http = http;
        this.requests = requests;
    }

    public void requireAction(String projectId, String capability) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        HttpServletRequest request = requests.getIfAvailable();
        String authorization = request == null ? null : request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            // 内部系统调用（调度触发实时作业）没有用户 JWT，以超级管理员身份直连；
            // 网关进来的请求始终携带 Authorization，仍走完整项目权限校验。
            if (MetadataHolder.isSuperAdmin()) {
                return;
            }
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String selected = request.getHeader("X-Project-Group-Id");
        if (selected != null && !projectId.equals(selected)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不属于当前项目");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        R<ProjectWorkspaceDto> response;
        try {
            response = http.exchange(WORKSPACE, HttpMethod.GET, new HttpEntity<Void>(headers),
                    new ParameterizedTypeReference<R<ProjectWorkspaceDto>>() {}, projectId).getBody();
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (response == null) {
            throw unavailable();
        }
        if (response.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (response.getCode() == ResultCode.NOT_FOUND.getCode() || response.getCode() == ResultCode.FORBIDDEN.getCode()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流不存在或不可访问");
        }
        ProjectWorkspaceDto workspace = response.getData();
        if (response.getCode() != 0 || workspace == null || !Objects.equals(projectId, workspace.projectGroupId())
                || workspace.capabilities() == null) {
            throw unavailable();
        }
        if (!workspace.capabilities().contains(capability)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前角色缺少该项目的工作流操作权限");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.SYSTEM_ERROR, "项目权限暂不可用，请重试");
    }
}
