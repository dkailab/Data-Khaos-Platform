package com.datakhaos.permission.api.service;

import com.datakhaos.common.model.R;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.ColumnPolicy;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RowPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限服务 REST 客户端（通过注册中心负载均衡调用 data-khaos-permission）。
 * 依赖一个 @LoadBalanced RestTemplate（bean 名 lbRestTemplate）。
 */
@Slf4j
public class PermissionApiClient {

    private final RestTemplate restTemplate;

    public PermissionApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取用户权限视图（角色 / 权限标识 / 菜单）
     */
    public UserPermissionDto getUserPermission(String userId) {
        try {
            ResponseEntity<R<UserPermissionDto>> resp = restTemplate.exchange(
                    "http://data-khaos-permission/api/permission/user/{userId}",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<UserPermissionDto>>() {
                    }, userId);
            R<UserPermissionDto> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData() == null ? new UserPermissionDto() : body.getData();
            }
        } catch (Exception e) {
            log.warn("调用权限服务查询用户权限失败: {}", e.getMessage());
        }
        return new UserPermissionDto();
    }

    /**
     * 获取用户在指定数据源/库表上拥有的表级权限集合
     */
    public List<Map<String, Object>> getUserTablePermissions(String userId) {
        try {
            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    "http://data-khaos-permission/api/permission/table/user/{userId}",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {
                    }, userId);
            R<List<Map<String, Object>>> body = resp.getBody();
            if (body != null && body.getCode() == 0) {
                return body.getData() == null ? new ArrayList<>() : body.getData();
            }
        } catch (Exception e) {
            log.warn("调用权限服务查询用户表权限失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 校验用户对某库表是否拥有指定操作权限
     */
    public boolean checkTablePermission(String userId, String datasourceId, String database, String table, String type) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("userId", userId);
            body.put("datasourceId", datasourceId);
            body.put("databaseName", database);
            body.put("tableName", table);
            body.put("permissionType", type);
            ResponseEntity<R<Boolean>> resp = restTemplate.exchange(
                    "http://data-khaos-permission/api/permission/table/check",
                    HttpMethod.POST, new HttpEntity<>(body),
                    new ParameterizedTypeReference<R<Boolean>>() {
                    });
            R<Boolean> r = resp.getBody();
            if (r != null && r.getCode() == 0) {
                return Boolean.TRUE.equals(r.getData());
            }
        } catch (Exception e) {
            log.warn("调用权限服务校验表权限失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 授予用户/角色表级权限（审批通过后自动授权）。
     *
     * @param permission 字段：datasourceId / databaseName / tableName / permissionType / userId / roleId / grantType
     */
    public boolean grantTablePermission(Map<String, Object> permission) {
        try {
            ResponseEntity<R<Void>> resp = restTemplate.exchange(
                    "http://data-khaos-permission/api/permission/table",
                    HttpMethod.POST, new HttpEntity<>(permission),
                    new ParameterizedTypeReference<R<Void>>() {
                    });
            R<Void> body = resp.getBody();
            return body != null && body.getCode() == 0;
        } catch (Exception e) {
            log.warn("调用权限服务自动授权失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 行/列级策略查询（供 SQL 改写使用） ====================

    /**
     * 查询用户在指定表上的行级权限策略列表
     */
    public List<RowPolicy> getRowPolicies(String userId, UserPermissionDto userPerm, String tableName) {
        List<String> roles = userPerm != null && userPerm.getRoles() != null ? userPerm.getRoles() : Collections.emptyList();
        List<String> groups = userPerm != null && userPerm.getProjectGroups() != null
                ? userPerm.getProjectGroups().stream().map(pg -> pg.getId()).collect(Collectors.toList())
                : Collections.emptyList();
        return doGetRowPolicies(userId, roles, groups, tableName);
    }

    /**
     * 查询用户在指定表上的列级权限策略列表
     */
    public List<ColumnPolicy> getColumnPolicies(String userId, UserPermissionDto userPerm, String tableName) {
        List<String> roles = userPerm != null && userPerm.getRoles() != null ? userPerm.getRoles() : Collections.emptyList();
        List<String> groups = userPerm != null && userPerm.getProjectGroups() != null
                ? userPerm.getProjectGroups().stream().map(pg -> pg.getId()).collect(Collectors.toList())
                : Collections.emptyList();
        return doGetColumnPolicies(userId, roles, groups, tableName);
    }

    private List<RowPolicy> doGetRowPolicies(String userId, List<String> roleIds, List<String> projectGroupIds, String tableName) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString("http://data-khaos-permission/api/permission/policy/row/user-table")
                    .queryParam("targetTable", tableName);
            if (userId != null) builder.queryParam("userId", userId);
            if (roleIds != null && !roleIds.isEmpty()) roleIds.forEach(r -> builder.queryParam("roleIds", r));
            if (projectGroupIds != null && !projectGroupIds.isEmpty()) projectGroupIds.forEach(g -> builder.queryParam("projectGroupIds", g));

            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});
            R<List<Map<String, Object>>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null) {
                return body.getData().stream()
                        .map(m -> new RowPolicy((String) m.get("targetTable"), (String) m.get("expression")))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("调用权限服务查询行权限策略失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<ColumnPolicy> doGetColumnPolicies(String userId, List<String> roleIds, List<String> projectGroupIds, String tableName) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString("http://data-khaos-permission/api/permission/policy/column/user-table")
                    .queryParam("targetTable", tableName);
            if (userId != null) builder.queryParam("userId", userId);
            if (roleIds != null && !roleIds.isEmpty()) roleIds.forEach(r -> builder.queryParam("roleIds", r));
            if (projectGroupIds != null && !projectGroupIds.isEmpty()) projectGroupIds.forEach(g -> builder.queryParam("projectGroupIds", g));

            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});
            R<List<Map<String, Object>>> body = resp.getBody();
            if (body != null && body.getCode() == 0 && body.getData() != null) {
                return body.getData().stream()
                        .map(m -> new ColumnPolicy((String) m.get("targetTable"), (String) m.get("columnName"), (String) m.get("maskType")))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("调用权限服务查询列权限策略失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
