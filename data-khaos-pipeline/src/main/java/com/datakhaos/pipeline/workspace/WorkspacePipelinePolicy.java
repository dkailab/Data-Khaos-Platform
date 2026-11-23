package com.datakhaos.pipeline.workspace;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.pipeline.entity.PipelineTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
class WorkspacePipelinePolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private final JdbcTemplate jdbc;
    private final String jwtSecret;

    WorkspacePipelinePolicy(JdbcTemplate jdbc, @Value("${data-khaos.jwt.secret}") String jwtSecret) {
        this.jdbc = jdbc;
        this.jwtSecret = jwtSecret;
    }

    String authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String token = authorization.substring("Bearer ".length());
        if (!JwtUtil.verify(token, jwtSecret)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Date expiry = JwtUtil.getExpiresAt(token);
        String userId = JwtUtil.getUid(token, jwtSecret);
        if (expiry == null || !expiry.toInstant().isAfter(Instant.now()) || StrUtil.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    void requireMember(String projectId, String userId) {
        List<String> capabilities = jdbc.query("""
                SELECT r.capability_flags
                FROM sg_project_group_member m
                JOIN sg_project_group g ON g.id = m.project_group_id AND g.status = 1
                JOIN sys_user u ON u.id = m.user_id AND u.status = 1
                LEFT JOIN sg_project_role r ON r.id = m.project_role_id AND r.status = 1
                    AND (r.project_group_id IS NULL OR r.project_group_id = g.id)
                    AND (r.org_id IS NULL OR r.org_id = g.org_id)
                WHERE m.project_group_id = ? AND m.user_id = ?
                """, (rs, row) -> rs.getString(1), projectId, userId);
        if (capabilities.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目不存在或不可访问");
        }
        String flags = capabilities.get(0);
        if (StrUtil.isBlank(flags) || !JSONUtil.isTypeJSONArray(flags) || JSONUtil.parseArray(flags).stream()
                .noneMatch(flag -> "task:develop".equals(flag) || "pipeline:run".equals(flag))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前角色缺少项目管道运行权限");
        }
    }

    Connections authorizeResources(PipelineTask task, String projectId, String bizDate) {
        if (task == null || !projectId.equals(task.getProjectGroupId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "项目内任务不存在或不可访问");
        }
        if (!Integer.valueOf(1).equals(task.getStatus()) || !"DB_SYNC".equals(task.getEngine())
                || !"REPLACE_BY_BIZ_DATE".equals(task.getWriteMode())
                || StrUtil.isNotBlank(task.getSourceQuery()) || StrUtil.isNotBlank(task.getFieldMapping())
                || !isIdentifier(task.getSourceTable()) || !isIdentifier(task.getTargetTable())
                || !isIdentifier(task.getDateColumn())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "项目空间仅支持启用的 DB-Sync 按日覆盖任务，需使用同名日期列与直接字段映射");
        }

        Map<String, Object> source = datasource(projectId, task.getSourceDsId());
        Map<String, Object> target = datasource(projectId, task.getTargetDsId());
        String sourceDb = (String) source.get("database_name");
        String targetDb = (String) target.get("database_name");
        String sourceTable = task.getSourceTable();
        String targetTable = task.getTargetTable();
        requireGrant(projectId, task.getSourceDsId(), sourceDb, sourceTable, "SELECT");
        requireGrant(projectId, task.getTargetDsId(), targetDb, targetTable, "INSERT");
        requireGrant(projectId, task.getTargetDsId(), targetDb, targetTable, "DELETE");
        rejectRowOrColumnPolicies(sourceDb, sourceTable);
        rejectRowOrColumnPolicies(targetDb, targetTable);
        String targetKey = tableIdentity(target, targetTable);
        if (tableIdentity(source, sourceTable).equals(targetKey)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "源表与目标表必须不同");
        }
        return new Connections(source, target, DigestUtil.sha256Hex(targetKey + "|" + bizDate));
    }

    private Map<String, Object> datasource(String projectId, String datasourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.ds_type, d.host, d.port, d.database_name, d.username, d.password
                FROM meta_datasource d
                JOIN sg_project_group_resource r ON r.resource_id = d.id AND r.resource_type = 'DATASOURCE'
                WHERE r.project_group_id = ? AND d.id = ? AND d.status = 1 AND d.ds_type = 'MYSQL'
                """, projectId, datasourceId);
        if (rows.size() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "源和目标必须是当前项目绑定且启用的 MySQL 数据源");
        }
        Map<String, Object> row = rows.get(0);
        String host = (String) row.get("host");
        Object port = row.get("port");
        if (host == null || !host.matches("[A-Za-z0-9.-]+") || !(port instanceof Number number)
                || number.intValue() < 1 || number.intValue() > 65535
                || !isIdentifier((String) row.get("database_name"))) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "数据源主机、端口或默认数据库配置无效");
        }
        return row;
    }

    private void requireGrant(String projectId, String datasourceId, String db, String table, String action) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_table_permission
                WHERE project_group_id = ? AND datasource_id = ? AND database_name = ? AND table_name = ?
                  AND grant_type = 'PROJECT_GROUP' AND status = 1 AND permission_type IN (?, 'ALL')
                """, Integer.class, projectId, datasourceId, db, table, action);
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "项目缺少表级授权：" + action);
        }
    }

    private void rejectRowOrColumnPolicies(String db, String table) {
        String qualified = (db + "." + table).toLowerCase(Locale.ROOT);
        String plain = table.toLowerCase(Locale.ROOT);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT target_table FROM sys_row_policy WHERE status = 1
                    UNION ALL SELECT target_table FROM sys_column_policy WHERE status = 1
                ) policies
                WHERE LOWER(REPLACE(target_table, '`', '')) IN (?, ?)
                """, Integer.class, plain, qualified);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "表存在行列策略，暂不支持项目空间直连同步");
        }
    }

    private static String tableIdentity(Map<String, Object> connection, String table) {
        return (connection.get("host") + ":" + connection.get("port") + "/"
                + connection.get("database_name") + "." + table).toLowerCase(Locale.ROOT);
    }

    private static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    record Connections(Map<String, Object> source, Map<String, Object> target, String activeKey) {}
}
