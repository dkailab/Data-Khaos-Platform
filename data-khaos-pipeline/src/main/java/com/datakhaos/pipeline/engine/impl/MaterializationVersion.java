package com.datakhaos.pipeline.engine.impl;

import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;

import java.sql.Connection;
import java.util.UUID;
import java.util.regex.Pattern;

final class MaterializationVersion {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private MaterializationVersion() {
    }

    static boolean enabled(String config) {
        return JSONUtil.isTypeJSONObject(config)
                && Boolean.TRUE.equals(JSONUtil.parseObj(config).getBool("serveMaterialization"));
    }

    static String record(Connection connection, String database, String table, String column,
                       String date, String instanceId) throws Exception {
        if (!identifier(database) || !identifier(table) || !identifier(column) || instanceId == null
                || "dk_materialization_version".equalsIgnoreCase(table)) {
            throw new BusinessException("受管物化要求普通 MySQL 标识符与管道实例 ID");
        }
        DbSyncEngine.requireBaseTable(connection, "dk_materialization_version", true);
        String sql = """
                INSERT INTO %s.dk_materialization_version
                    (target_table, biz_date, partition_column, instance_id, generation)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE partition_column = ?, instance_id = ?, generation = ?
                """.formatted(DbSyncEngine.quote(database));
        String generation = UUID.randomUUID().toString();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            statement.setString(1, table);
            statement.setString(2, date);
            statement.setString(3, column);
            statement.setString(4, instanceId);
            statement.setString(5, generation);
            statement.setString(6, column);
            statement.setString(7, instanceId);
            statement.setString(8, generation);
            statement.executeUpdate();
        }
        return generation;
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
