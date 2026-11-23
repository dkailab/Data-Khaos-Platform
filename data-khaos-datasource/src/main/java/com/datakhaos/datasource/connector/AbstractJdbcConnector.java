package com.datakhaos.datasource.connector;

import com.datakhaos.datasource.api.connector.DataSourceConnector;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.DsConfig;
import com.datakhaos.datasource.api.model.QueryResult;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 连接器基类：通过 JDBC 元数据实现库/表/字段枚举与 SQL 执行。
 * 子类只需声明类型、驱动类与 JDBC URL 模板。
 */
@Slf4j
public abstract class AbstractJdbcConnector implements DataSourceConnector {

    /** 查询返回的最大行数 */
    protected static final int MAX_ROWS = 1000;

    @Override
    public boolean testConnection(DsConfig config) {
        try (Connection conn = open(config)) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.warn("数据源连接测试失败 [{}]: {}", getType(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> getDatabases(DsConfig config) {
        List<String> result = new ArrayList<>();
        try (Connection conn = open(config)) {
            DatabaseMetaData meta = conn.getMetaData();
            // 优先读 schema；MySQL Connector/J 的 getSchemas() 在部分版本返回空，
            // 此时回退到 catalog（MySQL 的库即 catalog）。
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (name != null && !name.isBlank()) {
                        result.add(name);
                    }
                }
            }
            if (result.isEmpty()) {
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_CAT");
                        if (name != null && !name.isBlank()) {
                            result.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("获取数据库列表失败: " + e.getMessage(), e);
        }
        // 过滤 JDBC 内部系统库，避免元数据目录被系统 schema 污染
        List<String> systemSchemas = List.of("information_schema", "mysql", "performance_schema", "sys", "template0", "template1", "postgres");
        return result.stream()
                .filter(n -> !systemSchemas.contains(n.toLowerCase()))
                .distinct()
                .toList();
    }

    @Override
    public List<String> getTables(DsConfig config, String database) {
        List<String> result = new ArrayList<>();
        try (Connection conn = open(config)) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(metadataCatalog(database), database, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    result.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("获取表列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<ColumnInfo> getColumns(DsConfig config, String database, String table) {
        List<ColumnInfo> result = new ArrayList<>();
        try (Connection conn = open(config)) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, String> pks = new HashMap<>();
            try (ResultSet rs = meta.getPrimaryKeys(metadataCatalog(database), database, table)) {
                while (rs.next()) {
                    pks.put(rs.getString("COLUMN_NAME"), "1");
                }
            }
            int order = 1;
            try (ResultSet rs = meta.getColumns(metadataCatalog(database), database, table, "%")) {
                while (rs.next()) {
                    ColumnInfo info = new ColumnInfo();
                    info.setColumnName(rs.getString("COLUMN_NAME"));
                    info.setColumnType(rs.getString("TYPE_NAME"));
                    info.setColumnLength(rs.getInt("COLUMN_SIZE"));
                    info.setColumnScale(rs.getInt("DECIMAL_DIGITS"));
                    info.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    info.setPrimaryKey(pks.containsKey(rs.getString("COLUMN_NAME")));
                    info.setDefaultValue(rs.getString("COLUMN_DEF"));
                    info.setDescription(rs.getString("REMARKS"));
                    info.setSortOrder(order++);
                    result.add(info);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("获取字段信息失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public QueryResult executeQuery(DsConfig config, String sql, Map<String, Object> params) throws SQLException {
        long start = System.currentTimeMillis();
        try (Connection conn = open(config);
             Statement stmt = conn.createStatement()) {
            if (params != null && !params.isEmpty()) {
                // 简单参数替换：仅支持 :name 与 ? 占位（预留能力）
                sql = replaceParams(sql, params);
            }
            stmt.setQueryTimeout(30);
            boolean hasResult = stmt.execute(sql);
            QueryResult result = new QueryResult();
            if (hasResult) {
                try (ResultSet rs = stmt.getResultSet()) {
                    fillResult(result, rs);
                }
            } else {
                result.setUpdate(true);
                result.setRowCount(stmt.getUpdateCount());
            }
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    @Override
    public long getTableCount(DsConfig config, String database, String table) {
        String safeTable = table == null ? table : table.replaceAll("[^a-zA-Z0-9_$.]", "");
        try (Connection conn = open(config);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + safeTable)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            log.warn("统计表数据量失败 [{}]: {}", safeTable, e.getMessage());
            return -1;
        }
    }

    protected String metadataCatalog(String database) {
        return database;
    }

    protected Connection open(DsConfig config) throws SQLException {
        try {
            Class.forName(getDriverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("未找到数据源驱动类: " + getDriverClass() + "，请确认已引入对应 JDBC 驱动", e);
        }
        String url = buildJdbcUrl(config);
        return DriverManager.getConnection(url, config.getUsername(), config.getPassword());
    }

    /** host:port 拼接（使用配置端口；为空时不带端口） */
    protected String hostPort(DsConfig config) {
        return hostPort(config, 0);
    }

    /** host:port 拼接（端口为空时使用默认端口） */
    protected String hostPort(DsConfig config, int defaultPort) {
        int port = config.getPort() != null ? config.getPort() : defaultPort;
        return config.getHost() + (port > 0 ? ":" + port : "");
    }

    /** 数据库名（可为空，用于拼接 URL 路径段） */
    protected String db(DsConfig config) {
        return config.getDatabaseName() == null ? "" : config.getDatabaseName();
    }

    protected void fillResult(QueryResult result, ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            ColumnInfo info = new ColumnInfo();
            info.setColumnName(meta.getColumnLabel(i));
            info.setColumnType(meta.getColumnTypeName(i));
            info.setSortOrder(i);
            result.getColumns().add(info);
        }
        int count = 0;
        while (rs.next() && count < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
            }
            result.getRows().add(row);
            count++;
        }
        result.setRowCount(count);
    }

    /** 将 Clob/Blob 等转为可序列化对象 */
    protected Object normalize(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            long len = clob.length();
            return clob.getSubString(1, (int) Math.min(len, 4000));
        }
        if (value instanceof Blob blob) {
            byte[] bytes = blob.getBytes(1, (int) Math.min(blob.length(), 4096));
            return bytes;
        }
        return value;
    }

    private String replaceParams(String sql, Map<String, Object> params) {
        String result = sql;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object v = entry.getValue();
            String literal = v == null ? "NULL"
                    : (v instanceof Number ? v.toString() : "'" + String.valueOf(v).replace("'", "''") + "'");
            result = result.replace(":" + entry.getKey(), literal);
        }
        return result;
    }
}
