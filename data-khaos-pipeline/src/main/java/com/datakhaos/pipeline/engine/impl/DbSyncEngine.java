package com.datakhaos.pipeline.engine.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.pipeline.dto.PipelinePreflight;
import com.datakhaos.pipeline.engine.EngineUtils;
import com.datakhaos.pipeline.engine.PipelineEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB-Sync 兜底引擎：通过 JDBC 直连源/目标数据源完成同步，
 * 不依赖外部引擎二进制，保证最小闭环可用。
 *
 * Hive 到 MySQL 和达梦的分批加载由 HiveJdbcLoad 执行。
 */
@Slf4j
@Component
public class DbSyncEngine implements PipelineEngine {

    private final JdbcTemplate jdbcTemplate;
    private final String aesKey;

    public DbSyncEngine(JdbcTemplate jdbcTemplate,
                        @Value("${data-khaos.aes-key:dk-aes-key-16byte}") String aesKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.aesKey = aesKey;
    }

    @Override
    public String type() {
        return "DB_SYNC";
    }

    @Override
    public String name() {
        return "DB-Sync（内置直连同步）";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String buildRunConfig(PipelineTask task) {
        return JSONUtil.toJsonStr(Map.of(
                "source", task.getSourceTable(),
                "target", task.getTargetTable(),
                "mode", "JDBC 直连同步"));
    }

    @Override
    public int execute(PipelineTask task, PipelineInstance instance) throws Exception {
        if (StrUtil.isBlank(task.getSourceDsId()) || StrUtil.isBlank(task.getSourceTable())
                || StrUtil.isBlank(task.getTargetDsId()) || StrUtil.isBlank(task.getTargetTable())) {
            throw new BusinessException("DB-Sync 引擎需配置源/目标数据源与表");
        }
        Map<String, Object> source = EngineUtils.getDs(jdbcTemplate, task.getSourceDsId());
        Map<String, Object> target = EngineUtils.getDs(jdbcTemplate, task.getTargetDsId());
        return execute(task, instance, source, target, false);
    }

    public PipelinePreflight preflight(PipelineTask task, String bizDate) throws Exception {
        if (StrUtil.hasBlank(task.getSourceDsId(), task.getTargetDsId(), task.getSourceTable(), task.getTargetTable())) {
            throw new BusinessException("请先配置并保存源和目标数据源及表");
        }
        Map<String, Object> source = EngineUtils.getDs(jdbcTemplate, task.getSourceDsId());
        Map<String, Object> target = EngineUtils.getDs(jdbcTemplate, task.getTargetDsId());
        if (!hiveRelationalLoad(source, target)) {
            throw new BusinessException("当前支持 Hive 到 MySQL 或达梦的任务预检");
        }
        PipelineInstance context = new PipelineInstance();
        context.setBizDate(StrUtil.trimToNull(bizDate));
        return new HiveJdbcLoad(task, context, source, target, aesKey).preflight();
    }

    private boolean hiveRelationalLoad(Map<String, Object> source, Map<String, Object> target) {
        return "HIVE".equalsIgnoreCase((String) source.get("ds_type"))
                && ("MYSQL".equalsIgnoreCase((String) target.get("ds_type"))
                || "DM8".equalsIgnoreCase((String) target.get("ds_type")));
    }

    /** Runs with the exact data-source settings checked for the selected project. */
    public int executeWorkspace(PipelineTask task, PipelineInstance instance,
                                Map<String, Object> source, Map<String, Object> target) throws Exception {
        return execute(task, instance, source, target, true);
    }

    private int execute(PipelineTask task, PipelineInstance instance, Map<String, Object> source,
                        Map<String, Object> target, boolean workspace) throws Exception {
        if (hiveRelationalLoad(source, target)) {
            return new HiveJdbcLoad(task, instance, source, target, aesKey).execute();
        }
        EngineUtils.checkJdbc(source, target);
        if ("MYSQL".equalsIgnoreCase((String) target.get("ds_type"))) {
            instance.setTargetDatabaseName((String) target.get("database_name"));
        }

        String timeouts = workspace ? "&connectTimeout=5000&socketTimeout=45000" : "";
        String sourceUrl = EngineUtils.jdbcUrl(source) + timeouts;
        String targetUrl = EngineUtils.jdbcUrl(target) + timeouts;
        String sourceUser = (String) source.get("username");
        String sourcePwd = EngineUtils.decryptPwd((String) source.get("password"), aesKey);
        String targetUser = (String) target.get("username");
        String targetPwd = EngineUtils.decryptPwd((String) target.get("password"), aesKey);

        // 1. 读源（Hive 不支持反引号包裹 库.表，MySQL 需要反引号）
        //    sourceQuery 支持 ${bizDate} 占位（按业务日过滤/聚合）
        String sourceType = String.valueOf(source.get("ds_type")).toUpperCase();
        String selectSql;
        if (workspace) {
            selectSql = "SELECT * FROM " + quote(task.getSourceTable()) + " WHERE " + quote(task.getDateColumn()) + " = ?";
        } else if (StrUtil.isNotBlank(task.getSourceQuery())) {
            selectSql = task.getSourceQuery();
        } else if ("HIVE".equals(sourceType)) {
            selectSql = "SELECT * FROM " + task.getSourceTable();
        } else {
            selectSql = "SELECT * FROM `" + task.getSourceTable() + "`";
        }
        String writeMode = StrUtil.blankToDefault(task.getWriteMode(), "APPEND");
        String bizDate = instance.getBizDate();
        String dateColumn = task.getDateColumn();
        boolean replaceByBizDate = "REPLACE_BY_BIZ_DATE".equalsIgnoreCase(writeMode);
        if (selectSql.contains("${bizDate}") && StrUtil.isBlank(bizDate)) {
            throw new BusinessException("任务源查询包含 ${bizDate} 占位，但本次运行未传入业务日期");
        }
        if (replaceByBizDate && (StrUtil.isBlank(bizDate) || StrUtil.isBlank(dateColumn))) {
            throw new BusinessException("按日覆盖需传入业务日期并配置目标日期列");
        }
        if (replaceByBizDate || selectSql.contains("${bizDate}")) {
            validateBusinessDate(bizDate);
        }
        boolean trackPublication = replaceByBizDate && "MYSQL".equalsIgnoreCase((String) target.get("ds_type"));
        boolean managed = MaterializationVersion.enabled(task.getConfig());
        if (managed && !trackPublication) {
            throw new BusinessException("受管物化仅支持 MySQL 目标按业务日覆盖");
        }
        selectSql = selectSql.replace("${bizDate}", StrUtil.blankToDefault(bizDate, ""));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(sourceUrl, sourceUser, sourcePwd)) {
            if (workspace) {
                requireBaseTable(conn, task.getSourceTable(), false);
                try (PreparedStatement query = conn.prepareStatement(selectSql)) {
                    query.setString(1, instance.getBizDate());
                    query.setQueryTimeout(30);
                    query.setMaxRows(100_001);
                    try (ResultSet result = query.executeQuery()) {
                        readRows(result, columns, rows, true);
                    }
                }
            } else {
                try (Statement query = conn.createStatement(); ResultSet result = query.executeQuery(selectSql)) {
                    readRows(result, columns, rows, false);
                }
            }
        }

        // 2. 字段映射（{"源列":"目标列"}，缺省同名字段）
        Map<String, String> mapping = resolveMapping(task.getFieldMapping(), columns);

        // 3. 写目标（同一事务内完成分区覆盖）
        //    写入策略：REPLACE_BY_BIZ_DATE 先删当日旧数据再插新结果；APPEND 直接追加。
        //    读取成功且零行时同样提交（空分区，清理旧数据）；写入失败回滚保留原分区。
        if (replaceByBizDate) {
            validatePartitionRows(mapping, rows, dateColumn, bizDate);
        }
        List<String> targetCols = new ArrayList<>(mapping.values());
        String targetTable = task.getTargetTable();
        String insertSql = "INSERT INTO " + quote(targetTable) + " ("
                + String.join(",", targetCols.stream().map(DbSyncEngine::quote).toList()) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(targetCols.size(), "?")) + ")";
        try (Connection conn = DriverManager.getConnection(targetUrl, targetUser, targetPwd)) {
            String targetDatabase = conn.getCatalog();
            if (workspace || trackPublication) {
                requireBaseTable(conn, targetTable, true);
            }
            if (trackPublication) {
                lockPartition(conn, targetDatabase, targetTable, bizDate);
            }
            conn.setAutoCommit(false);
            try {
                if (replaceByBizDate) {
                    String deleteSql = "DELETE FROM " + quote(targetTable) + " WHERE " + quote(dateColumn) + " = ?";
                    try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                        if (workspace) del.setQueryTimeout(30);
                        del.setString(1, bizDate);
                        int deleted = del.executeUpdate();
                        log.info("按业务日清理目标分区: {}.{}.{} = {}, 删除 {} 行",
                                targetUrl, targetTable, dateColumn, bizDate, deleted);
                    }
                }
                int count = 0;
                if (!rows.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        if (workspace) ps.setQueryTimeout(30);
                        for (Map<String, Object> row : rows) {
                            int idx = 1;
                            for (String srcCol : mapping.keySet()) {
                                ps.setObject(idx++, row.get(srcCol));
                            }
                            ps.addBatch();
                            count++;
                            if (count % 500 == 0) {
                                ps.executeBatch();
                            }
                        }
                        ps.executeBatch();
                    }
                } else {
                    log.info("源查询结果为零行：提交空分区（清理旧数据）: {} {}.{}",
                            targetTable, dateColumn, bizDate);
                }
                String generation = managed
                        ? MaterializationVersion.record(conn, targetDatabase, targetTable, dateColumn, bizDate, instance.getId())
                        : null;
                LocalDateTime publishedAt = trackPublication ? publicationTime(conn) : null;
                try {
                    conn.commit();
                } catch (SQLException e) {
                    if (managed) {
                        throw new PipelineEngine.CommitUnconfirmedException("受管物化提交结果未知，请核查分区", e);
                    }
                    throw e;
                }
                instance.setTargetDatabaseName(targetDatabase);
                instance.setPublishedAt(publishedAt);
                instance.setMaterializationGeneration(generation);
                return count;
            } catch (PipelineEngine.CommitUnconfirmedException e) {
                throw e;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    if (managed) {
                        e.addSuppressed(rollbackError);
                        throw new PipelineEngine.CommitUnconfirmedException("受管物化回滚结果未知，请核查分区", e);
                    }
                    throw rollbackError;
                }
                log.warn("写入目标失败，已回滚保留原分区: {} {}, error={}", targetTable, writeMode, e.getMessage());
                throw e;
            }
        }
    }

    private void validateBusinessDate(String bizDate) {
        try {
            if (bizDate.length() != 10 || !LocalDate.parse(bizDate).toString().equals(bizDate)) {
                throw new BusinessException("按日覆盖的业务日期须为 YYYY-MM-DD");
            }
        } catch (DateTimeParseException e) {
            throw new BusinessException("按日覆盖的业务日期须为 YYYY-MM-DD");
        }
    }

    private void validatePartitionRows(Map<String, String> mapping, List<Map<String, Object>> rows,
                                       String dateColumn, String bizDate) {
        String sourceDateColumn = mapping.entrySet().stream()
                .filter(entry -> dateColumn.equalsIgnoreCase(entry.getValue()))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new BusinessException("源查询结果缺少映射到目标日期列的字段"));
        if (rows.stream().anyMatch(row -> !bizDate.equals(String.valueOf(row.get(sourceDateColumn))))) {
            throw new BusinessException("源查询结果包含非本业务日期的数据，已停止覆盖");
        }
    }

    // Serialize writers of the same physical partition on the target server,
    // including connections using two datasource aliases. Closing the connection releases the lock.
    static void lockPartition(Connection conn, String database, String table, String bizDate) throws Exception {
        String name = DigestUtil.sha256Hex(("data-khaos|" + database + "|" + table + "|" + bizDate)
                .toLowerCase(Locale.ROOT));
        try (PreparedStatement statement = conn.prepareStatement("SELECT GET_LOCK(?, 30)")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new BusinessException("未获得目标分区写入锁，请稍后重试");
                }
            }
        }
    }

    static LocalDateTime publicationTime(Connection conn) throws Exception {
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery("SELECT UTC_TIMESTAMP(6)")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }

    private void readRows(ResultSet result, List<String> columns, List<Map<String, Object>> rows,
                          boolean workspace) throws Exception {
        ResultSetMetaData metadata = result.getMetaData();
        List<String> rawColumns = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            rawColumns.add(label);
            columns.add(normalizeColumn(label));
        }
        while (result.next()) {
            if (workspace && rows.size() == 100_000) {
                throw new BusinessException("单日源数据超过 100000 行，请缩小任务处理范围");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                row.put(columns.get(index), result.getObject(rawColumns.get(index)));
            }
            rows.add(row);
        }
    }

    static void requireBaseTable(Connection connection, String tableName, boolean transactional) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT engine FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ? AND table_type = 'BASE TABLE'
                """)) {
            statement.setQueryTimeout(10);
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || (transactional && !"InnoDB".equalsIgnoreCase(result.getString(1)))) {
                    throw new BusinessException(transactional ? "目标表必须是 InnoDB 基础表" : "源表必须是 MySQL 基础表");
                }
            }
        }
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** 源表新列名 → 目标列名 */
    private Map<String, String> resolveMapping(String fieldMapping, List<String> columns) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(fieldMapping)) {
            JSONObject obj = JSONUtil.parseObj(fieldMapping);
            for (String col : columns) {
                mapping.put(col, obj.containsKey(col) ? obj.getStr(col) : col);
            }
        } else {
            for (String col : columns) {
                mapping.put(col, col);
            }
        }
        return mapping;
    }

    /** 规范化列名：去掉 Hive 结果集列标签中的 表名. 前缀（如 biz_user.id → id） */
    private String normalizeColumn(String label) {
        if (label == null) {
            return label;
        }
        int dot = label.lastIndexOf('.');
        return dot > 0 ? label.substring(dot + 1) : label;
    }
}