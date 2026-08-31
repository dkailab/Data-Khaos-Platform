package com.datakhaos.query.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.security.SqlAuditUtil;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.ColumnPolicy;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RewriteResult;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RowPolicy;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.config.QueryProperties;
import com.datakhaos.query.dto.QueryExecuteRequest;
import com.datakhaos.query.entity.QueryHistory;
import com.datakhaos.query.mapper.QueryHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询服务：本地 SQL 审核 -> 行/列权限改写 ->（可选）表权限校验 -> 调用数据源服务执行 -> 记录历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    /** 简单提取 FROM / JOIN 表名 */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*)",
            Pattern.CASE_INSENSITIVE);

    private final QueryHistoryMapper historyMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final PermissionApiClient permissionApiClient;
    private final QueryProperties properties;

    /** 执行查询 */
    public QueryResult execute(QueryExecuteRequest request, String userId) {
        if (StrUtil.isBlank(request.getDatasourceId())) {
            throw new BusinessException("数据源ID不能为空");
        }
        // 1. 本地 SQL 审核
        String sql = SqlAuditUtil.audit(request.getSql());

        // 2. 行/列权限 SQL 改写
        sql = applyPermissionRewrite(sql, userId);

        // 3. 可选表权限校验
        if (properties.isPermissionCheckEnabled() && StrUtil.isNotBlank(userId) && !MetadataHolder.isSuperAdmin()) {
            checkTablePermission(request, sql, userId);
        }

        // 4. 调用数据源服务执行（服务端二次审核）
        long start = System.currentTimeMillis();
        R<QueryResult> result = datasourceApiClient.executeRaw(request.getDatasourceId(), sql);
        long cost = System.currentTimeMillis() - start;
        if (result == null || result.getCode() != 0) {
            String error = result == null ? "查询失败" : result.getMsg();
            saveHistory(userId, request, sql, 0, cost, 0, error);
            throw new BusinessException(error);
        }
        QueryResult data = result.getData();
        saveHistory(userId, request, sql, 1, cost,
                data == null ? 0 : (data.getRowCount() == null ? 0 : data.getRowCount()), null);
        return data;
    }

    /**
     * 提取 SQL 中涉及的所有表名，查询行/列权限策略并改写 SQL。
     * 超级管理员或未配置策略时直接返回原 SQL。
     */
    private String applyPermissionRewrite(String sql, String userId) {
        if (StrUtil.isBlank(sql)) return sql;
        if (StrUtil.isBlank(userId) || MetadataHolder.isSuperAdmin()) return sql;

        try {
            // 获取用户权限上下文
            UserPermissionDto userPerm = permissionApiClient.getUserPermission(userId);
            if (isNoPoliciesAvailable(userPerm)) return sql;

            // 提取 SQL 中涉及的表名
            Set<String> tables = extractTableNames(sql);
            if (tables.isEmpty()) return sql;

            // 逐表查询策略
            List<RowPolicy> rowPolicies = new ArrayList<>();
            List<ColumnPolicy> columnPolicies = new ArrayList<>();
            for (String table : tables) {
                rowPolicies.addAll(permissionApiClient.getRowPolicies(userId, userPerm, table));
                columnPolicies.addAll(permissionApiClient.getColumnPolicies(userId, userPerm, table));
            }

            if (rowPolicies.isEmpty() && columnPolicies.isEmpty()) return sql;

            // 改写 SQL
            RewriteResult rewriteResult = SqlRewriteEngine.rewrite(sql, rowPolicies, columnPolicies);
            if (rewriteResult.isChanged()) {
                log.info("SQL 改写生效，表={}, 行策略={}, 列策略={}",
                        tables, rewriteResult.getAppliedRows().size(), rewriteResult.getAppliedColumns().size());
                return rewriteResult.getSql();
            }
        } catch (Exception e) {
            log.warn("SQL 权限改写异常，使用原始 SQL: {}", e.getMessage());
        }
        return sql;
    }

    /** 快速判断用户在该 SQL 涉及的表上是否可能无任何策略（减少无效远程调用） */
    private boolean isNoPoliciesAvailable(UserPermissionDto userPerm) {
        // 如用户不在任何项目组 / 不担任任何角色 → 可加快速路径判断；此处保守返回 false 让逐表查询决定
        return false;
    }

    /** 从 SQL 中提取所有 FROM/JOIN 涉及的表名 */
    private Set<String> extractTableNames(String sql) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (StrUtil.isNotBlank(table)) {
                // 形如 schema.table 只取 table 部分
                int dot = table.lastIndexOf('.');
                tables.add(dot >= 0 ? table.substring(dot + 1) : table);
            }
        }
        return tables;
    }

    /** 查询历史（分页） */
    public PageResult<QueryHistory> history(long current, long size, String userId) {
        Page<QueryHistory> page = historyMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<QueryHistory>()
                        .eq(StrUtil.isNotBlank(userId), QueryHistory::getUserId, userId)
                        .orderByDesc(QueryHistory::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public QueryHistory historyDetail(String id) {
        QueryHistory history = historyMapper.selectById(id);
        if (history == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "查询历史不存在: " + id);
        }
        return history;
    }

    // ---------- 私有方法 ----------

    /** 表权限校验：仅当能从 SQL 中识别出简单 FROM 表时执行 */
    private void checkTablePermission(QueryExecuteRequest request, String sql, String userId) {
        Pattern fromPattern = Pattern.compile(
                "\\bFROM\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = fromPattern.matcher(sql);
        if (!matcher.find()) {
            return;
        }
        String table = matcher.group(1);
        boolean allowed = permissionApiClient.checkTablePermission(
                userId, request.getDatasourceId(), request.getDatabaseName(), table, "SELECT");
        if (!allowed) {
            throw new BusinessException("没有对表 " + table + " 的查询权限");
        }
    }

    private void saveHistory(String userId, QueryExecuteRequest request, String sql,
                             int status, long costMs, int rowCount, String errorMessage) {
        try {
            QueryHistory history = new QueryHistory();
            history.setUserId(userId);
            history.setDatasourceId(request.getDatasourceId());
            history.setDatabaseName(request.getDatabaseName());
            history.setSqlText(sql);
            history.setStatus(status);
            history.setCostMs(costMs);
            history.setRowCount(rowCount);
            history.setErrorMessage(errorMessage);
            historyMapper.insert(history);
        } catch (Exception e) {
            log.warn("记录查询历史失败: {}", e.getMessage());
        }
    }
}
