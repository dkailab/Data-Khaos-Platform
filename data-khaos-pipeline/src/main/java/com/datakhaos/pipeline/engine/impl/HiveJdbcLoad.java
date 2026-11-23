package com.datakhaos.pipeline.engine.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.security.SqlAuditUtil;
import com.datakhaos.datasource.api.model.JdbcConnectionUrls;
import com.datakhaos.pipeline.dto.PipelinePreflight;
import com.datakhaos.pipeline.engine.EngineUtils;
import com.datakhaos.pipeline.engine.PipelineEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
final class HiveJdbcLoad {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#-]*");
    private static final Pattern SELECT = Pattern.compile("^SELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final String BIZ_DATE = "${bizDate}";

    private final PipelineTask task;
    private final PipelineInstance instance;
    private final Map<String, Object> source;
    private final Map<String, Object> target;
    private final String aesKey;
    private final boolean dameng;
    private final boolean managed;
    private final LocalDate partitionDate;
    private final String sourceSql;
    private final int batchSize;
    private final int timeout;

    HiveJdbcLoad(PipelineTask task, PipelineInstance instance, Map<String, Object> source,
                 Map<String, Object> target, String aesKey) {
        this.task = task;
        this.instance = instance;
        this.source = source;
        this.target = target;
        this.aesKey = aesKey;
        this.dameng = "DM8".equalsIgnoreCase((String) target.get("ds_type"));
        String mode = StrUtil.blankToDefault(task.getWriteMode(), "APPEND");
        if ("REPLACE_BY_BIZ_DATE".equalsIgnoreCase(mode)) {
            partitionDate = businessDate(instance.getBizDate());
            identifier(task.getDateColumn());
        } else if ("APPEND".equalsIgnoreCase(mode)) {
            partitionDate = null;
        } else {
            throw new BusinessException("Hive DB-Sync 仅支持追加和按业务日覆盖");
        }
        JSONObject config = StrUtil.isBlank(task.getConfig()) ? new JSONObject() : JSONUtil.parseObj(task.getConfig());
        managed = MaterializationVersion.enabled(task.getConfig());
        if (managed && (dameng || partitionDate == null)) {
            throw new BusinessException("受管物化仅支持 MySQL 目标按业务日覆盖");
        }
        batchSize = bounded(config, "batchSize", 500, 5000);
        timeout = bounded(config, "timeoutSeconds", 600, 3600);
        sourceSql = sourceSql(config);
    }

    int execute() throws Exception {
        int rows = 0;
        boolean committed = false;
        try (Connection sourceConnection = EngineUtils.open(source, aesKey);
             Connection targetConnection = EngineUtils.open(target, aesKey)) {
            TargetTable table = targetTable(targetConnection);
            instance.setTargetDatabaseName(table.database());
            instance.setTargetTable(table.table());

            targetConnection.setAutoCommit(false);
            LocalDateTime publishedAt;
            String generation;
            try {
                rows = copy(sourceConnection, targetConnection, table);
                generation = managed ? MaterializationVersion.record(targetConnection, table.database(), table.table(),
                        task.getDateColumn(), partitionDate.toString(), instance.getId()) : null;
                publishedAt = partitionDate != null && !dameng ? DbSyncEngine.publicationTime(targetConnection) : null;
            } catch (Exception e) {
                try {
                    targetConnection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                    throw new PipelineEngine.CommitUnconfirmedException("未确认目标事务已回滚，请核查分区", e);
                }
                throw e;
            }
            try {
                targetConnection.commit();
            } catch (SQLException e) {
                throw new PipelineEngine.CommitUnconfirmedException("目标提交结果未知，请核查分区后再重跑", e);
            }
            committed = true;
            instance.setPublishedAt(publishedAt);
            instance.setMaterializationGeneration(generation);
        } catch (SQLException e) {
            if (!committed) throw e;
            log.warn("Hive 加载已提交，关闭 JDBC 连接时失败: {}", e.getMessage());
        }
        return rows;
    }

    PipelinePreflight preflight() throws Exception {
        try (Connection sourceConnection = EngineUtils.open(source, aesKey);
             Connection targetConnection = EngineUtils.open(target, aesKey);
             Statement query = sourceConnection.createStatement()) {
            TargetTable table = targetTable(targetConnection);
            configureSourceQuery(query, 1, Math.min(timeout, 30));
            query.setMaxRows(1);
            try (ResultSet rows = query.executeQuery(sourceSql)) {
                List<Column> columns = columns(rows.getMetaData(), targetConnection, table.sql());
                Column partition = partitionColumn(columns);
                boolean found = rows.next();
                if (found && partition != null) {
                    verifyPartitionDate(rows.getObject(columns.indexOf(partition) + 1));
                }
                List<String> warnings = new ArrayList<>();
                if (!found && partition != null) {
                    warnings.add("当前查询没有样例行。正式运行读到零行时会清理目标业务日。");
                }
                for (Column column : columns) {
                    if (decimal(column.sourceJdbcType()) && decimal(column.targetType()) && column.targetPrecision() > 0
                            && column.sourcePrecision() > 0
                            && (column.sourceScale() > column.targetScale()
                            || column.sourcePrecision() - column.sourceScale() > column.targetPrecision() - column.targetScale())) {
                        warnings.add("目标字段 " + column.target() + " 的整数位或小数位少于源字段 " + column.source());
                    }
                }
                warnings.add("目标新增和删除权限、全量数据及正式写入的事务结果需在运行时确认。");
                List<PipelinePreflight.Mapping> mappings = columns.stream()
                        .map(column -> new PipelinePreflight.Mapping(column.source(), column.sourceTypeName(),
                                column.target(), column.targetTypeName(), column.equals(partition)))
                        .toList();
                return new PipelinePreflight(table.database(), table.table(), instance.getBizDate(),
                        partitionDate == null ? "APPEND" : "REPLACE_BY_BIZ_DATE", batchSize, found, mappings, warnings);
            }
        } catch (SQLException e) {
            log.warn("Hive 管道预检 JDBC 失败: taskId={}, state={}, vendorCode={}",
                    task.getId(), e.getSQLState(), e.getErrorCode());
            String code = e.getSQLState() != null && e.getSQLState().matches("[A-Za-z0-9]{5}")
                    ? e.getSQLState() + "/" : "";
            throw new BusinessException("读取源查询或目标表结构失败（" + code + e.getErrorCode()
                    + "），请检查数据源连接、表名与读取权限");
        }
    }

    private static boolean decimal(int type) {
        return type == Types.DECIMAL || type == Types.NUMERIC;
    }

    private static void configureSourceQuery(Statement query, int fetchSize, int timeoutSeconds) throws SQLException {
        query.setFetchSize(fetchSize);
        query.setQueryTimeout(timeoutSeconds);
    }

    private Column partitionColumn(List<Column> columns) {
        if (partitionDate == null) return null;
        Column partition = columns.stream()
                .filter(column -> column.target().equalsIgnoreCase(task.getDateColumn()))
                .findFirst().orElseThrow(() -> new BusinessException("源字段未映射到目标日期列"));
        int type = partition.targetType();
        if (!textType(type) && type != Types.DATE && type != Types.TIMESTAMP) {
            throw new BusinessException("目标日期列须为 DATE、TIMESTAMP 或 ISO 日期字符串列");
        }
        return partition;
    }

    private void verifyPartitionDate(Object value) {
        if (!partitionDate.equals(rowDate(value))) {
            throw new BusinessException("Hive 查询返回了空日期或跨业务日数据，已停止覆盖");
        }
    }

    private int copy(Connection sourceConnection, Connection targetConnection, TargetTable table) throws Exception {
        try (Statement query = sourceConnection.createStatement()) {
            configureSourceQuery(query, batchSize, timeout);
            try (ResultSet rows = query.executeQuery(sourceSql)) {
                List<Column> columns = columns(rows.getMetaData(), targetConnection, table.sql());
                Column partition = partitionColumn(columns);
                if (partition != null) {
                    clearPartition(targetConnection, table, partition);
                }
                String targets = columns.stream().map(Column::target).map(this::quoteTarget).collect(Collectors.joining(", "));
                String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
                try (PreparedStatement insert = targetConnection.prepareStatement(
                        "INSERT INTO " + table.sql() + " (" + targets + ") VALUES (" + placeholders + ")")) {
                    insert.setQueryTimeout(timeout);
                    return batches(rows, insert, columns, partition);
                }
            }
        }
    }

    private int batches(ResultSet rows, PreparedStatement insert, List<Column> columns, Column partition) throws SQLException {
        int count = 0;
        int pending = 0;
        while (rows.next()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new BusinessException("Hive 加载已中断，正在回滚");
            }
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                Object value = rows.getObject(index + 1);
                if (column.equals(partition)) {
                    verifyPartitionDate(value);
                    if (textType(column.targetType())) value = partitionDate.toString();
                    else if (value instanceof String) value = Date.valueOf(partitionDate);
                }
                bind(insert, index + 1, column.targetType(), value);
            }
            insert.addBatch();
            count = Math.incrementExact(count);
            if (++pending == batchSize) {
                flush(insert, pending);
                pending = 0;
            }
        }
        if (pending != 0) flush(insert, pending);
        return count;
    }

    private static void flush(PreparedStatement insert, int expected) throws SQLException {
        int[] updates = insert.executeBatch();
        if (updates.length != expected || java.util.Arrays.stream(updates).anyMatch(n -> n != 1 && n != Statement.SUCCESS_NO_INFO)) {
            throw new SQLException("目标批次写入结果与提交行数不一致");
        }
        if (insert.getWarnings() != null) throw insert.getWarnings();
        insert.clearBatch();
    }

    private TargetTable targetTable(Connection connection) throws Exception {
        String[] names = tableParts(task.getTargetTable());
        String table = names[names.length - 1];
        String database;
        if (dameng) {
            database = JdbcConnectionUrls.damengIdentifier(StrUtil.blankToDefault((String) target.get("database_name"),
                    (String) target.get("username")));
            table = JdbcConnectionUrls.damengIdentifier(table);
        } else {
            database = connection.getCatalog();
        }
        if (names.length == 2 && !(dameng ? database.equalsIgnoreCase(names[0]) : names[0].equals(database))) {
            throw new BusinessException("目标表必须位于目标数据源配置的库或模式内");
        }
        if (dameng) {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?")) {
                check.setQueryTimeout(timeout);
                check.setString(1, database);
                check.setString(2, table);
                try (ResultSet result = check.executeQuery()) {
                    if (!result.next()) throw new BusinessException("达梦目标基础表不存在或不可访问");
                }
            }
        } else {
            DbSyncEngine.requireBaseTable(connection, table, true);
        }
        return new TargetTable(database, table, quoteTarget(database) + "." + quoteTarget(table));
    }

    private List<Column> columns(ResultSetMetaData sourceColumns, Connection connection, String targetTable) throws SQLException {
        Map<String, TargetColumn> available = new LinkedHashMap<>();
        try (Statement describe = connection.createStatement()) {
            describe.setQueryTimeout(timeout);
            try (ResultSet empty = describe.executeQuery("SELECT * FROM " + targetTable + " WHERE 1 = 0")) {
                ResultSetMetaData meta = empty.getMetaData();
                for (int index = 1; index <= meta.getColumnCount(); index++) {
                    String name = meta.getColumnName(index);
                    if (available.putIfAbsent(fold(name), new TargetColumn(name, meta.getColumnType(index),
                            meta.getColumnTypeName(index), meta.getPrecision(index), meta.getScale(index))) != null) {
                        throw new BusinessException("目标存在无法区分大小写的字段: " + name);
                    }
                }
            }
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(task.getFieldMapping())) {
            JSONUtil.parseObj(task.getFieldMapping()).forEach((key, value) -> {
                if (!(value instanceof String name) || name.isBlank() || mapping.putIfAbsent(fold(key), name) != null) {
                    throw new BusinessException("字段映射须为不重复的源列到目标列名称");
                }
            });
        }
        List<Column> selected = new ArrayList<>();
        Set<String> sourceNames = new HashSet<>();
        Set<String> targetNames = new HashSet<>();
        for (int index = 1; index <= sourceColumns.getColumnCount(); index++) {
            String label = sourceColumns.getColumnLabel(index);
            String name = label.substring(label.lastIndexOf('.') + 1);
            if (!sourceNames.add(fold(name))) throw new BusinessException("Hive 查询字段重复，请使用唯一别名: " + name);
            String configured = mapping.getOrDefault(fold(name), name);
            TargetColumn column = available.get(fold(configured));
            if (column == null || !targetNames.add(fold(column.name()))) {
                throw new BusinessException("目标字段不存在或被重复映射: " + configured);
            }
            selected.add(new Column(name, sourceColumns.getColumnTypeName(index), sourceColumns.getColumnType(index),
                    sourceColumns.getPrecision(index), sourceColumns.getScale(index),
                    column.name(), column.type(), column.typeName(), column.precision(), column.scale()));
        }
        if (!sourceNames.containsAll(mapping.keySet())) throw new BusinessException("字段映射包含不存在的 Hive 源列");
        return selected;
    }

    private void clearPartition(Connection connection, TargetTable table, Column partition) throws Exception {
        boolean textual = textType(partition.targetType());
        if (dameng) {
            try (Statement lock = connection.createStatement()) {
                lock.setQueryTimeout(timeout);
                lock.execute("LOCK TABLE " + table.sql() + " IN SHARE INTENT EXCLUSIVE MODE NOWAIT");
            }
        } else {
            DbSyncEngine.lockPartition(connection, table.database(), table.table(), partitionDate.toString());
        }
        String column = quoteTarget(partition.target());
        String predicate = textual ? column + " = ?" : column + " >= ? AND " + column + " < ?";
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table.sql() + " WHERE " + predicate)) {
            delete.setQueryTimeout(timeout);
            if (textual) {
                delete.setString(1, partitionDate.toString());
            } else {
                delete.setDate(1, Date.valueOf(partitionDate));
                delete.setDate(2, Date.valueOf(partitionDate.plusDays(1)));
            }
            delete.executeUpdate();
        }
    }

    private String sourceSql(JSONObject config) {
        if (StrUtil.isNotBlank(task.getSourceQuery())) {
            String query = SqlAuditUtil.audit(task.getSourceQuery());
            if (!SELECT.matcher(query).find()) throw new BusinessException("Hive 源查询须为单条 SELECT");
            if (partitionDate != null && !query.contains(BIZ_DATE)) {
                throw new BusinessException("按日覆盖的 Hive 源查询须使用 ${bizDate} 按日期过滤");
            }
            return query.contains(BIZ_DATE) ? query.replace(BIZ_DATE, businessDate(instance.getBizDate()).toString()) : query;
        }
        String sql = "SELECT * FROM " + java.util.Arrays.stream(tableParts(task.getSourceTable()))
                .map(DbSyncEngine::quote).collect(Collectors.joining("."));
        if (partitionDate == null) return sql;
        String col = StrUtil.blankToDefault(config.getStr("sourceDateColumn"), task.getDateColumn());
        return sql + " WHERE " + DbSyncEngine.quote(identifier(col)) + " = '" + partitionDate + "'";
    }

    private String quoteTarget(String name) {
        return dameng ? "\"" + name.replace("\"", "\"\"") + "\"" : DbSyncEngine.quote(name);
    }

    private static void bind(PreparedStatement statement, int index, int type, Object value) throws SQLException {
        if (value == null) statement.setNull(index, type);
        else if (value instanceof Date date) statement.setDate(index, date);
        else if (value instanceof Timestamp timestamp) statement.setTimestamp(index, timestamp);
        else if (value instanceof LocalDate date) statement.setDate(index, Date.valueOf(date));
        else if (value instanceof LocalDateTime timestamp) statement.setTimestamp(index, Timestamp.valueOf(timestamp));
        else if (value instanceof java.math.BigDecimal decimal) statement.setBigDecimal(index, decimal);
        else if (value instanceof byte[] bytes) statement.setBytes(index, bytes);
        else if (value instanceof Boolean bool) statement.setBoolean(index, bool);
        else statement.setObject(index, value);
    }

    private static boolean textType(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.NCHAR || type == Types.NVARCHAR;
    }

    private static LocalDate rowDate(Object value) {
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof Timestamp time) return time.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDate date) return date;
        if (value instanceof LocalDateTime time) return time.toLocalDate();
        return value instanceof String text ? businessDate(text) : null;
    }

    private static LocalDate businessDate(String value) {
        if (value != null && value.length() == 10) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.getYear() >= 1000 && date.getYear() <= 9998) return date;
            } catch (DateTimeParseException ignored) {
                // Report a consistent validation error below.
            }
        }
        throw new BusinessException("Hive 加载业务日期须为 YYYY-MM-DD");
    }

    private static String[] tableParts(String value) {
        if (value == null) throw new BusinessException("请填写表名");
        String[] parts = value.split("\\.", -1);
        if (parts.length > 2) throw new BusinessException("表名格式为 表 或 库.表");
        for (String part : parts) identifier(part);
        return parts;
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new BusinessException("表名和日期列须使用常规标识符");
        }
        return value;
    }

    private static int bounded(JSONObject config, String name, int fallback, int max) {
        Integer value = config.getInt(name, fallback);
        if (value == null || value < 1 || value > max) throw new BusinessException(name + " 须在 1 至 " + max + " 之间");
        return value;
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record Column(String source, String sourceTypeName, int sourceJdbcType, int sourcePrecision,
                          int sourceScale, String target, int targetType, String targetTypeName,
                          int targetPrecision, int targetScale) {}
    private record TargetColumn(String name, int type, String typeName, int precision, int scale) {}
    private record TargetTable(String database, String table, String sql) {}
}
