package com.datakhaos.query.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.query.dto.SqlCompleteRequest;
import com.datakhaos.query.dto.SqlCompleteResult;
import com.datakhaos.query.dto.SqlParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OneSQL 增强服务：SQL 补全、格式化、解析、元数据提示。
 * <p>
 * 引入 JSQLParser 解决正则解析的 CTE/UNION/嵌套子查询遗漏问题（见 roadmap 阶段六风险 #1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnesqlService {

    private static final List<String> SQL_KEYWORDS = Arrays.asList(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "EXISTS",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY",
            "ORDER", "HAVING", "LIMIT", "OFFSET", "AS", "DISTINCT", "ALL",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
            "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "WITH", "UNION",
            "CASE", "WHEN", "THEN", "ELSE", "END", "BETWEEN", "LIKE", "IS",
            "NULL", "TRUE", "FALSE", "CAST", "CONCAT", "COUNT", "SUM", "AVG",
            "MIN", "MAX", "IFNULL", "COALESCE", "DATE_FORMAT", "NOW", "CURDATE"
    );

    private final DatasourceApiClient datasourceApiClient;

    // ========================= SQL 补全 =========================

    /**
     * 根据当前光标上下文返回补全建议。
     * <p>
     * 简单策略：从 SQL 末尾向前分析，判断当前应该补全的是表名、列名还是关键字。
     */
    public SqlCompleteResult complete(SqlCompleteRequest request) {
        SqlCompleteResult result = new SqlCompleteResult();
        List<SqlCompleteResult.CompletionItem> items = new ArrayList<>();

        String sql = request.getSql() == null ? "" : request.getSql();
        int cursorPos = Math.min(request.getCursorPosition() == null ? sql.length() : request.getCursorPosition(), sql.length());

        // 提取当前词（从光标位置往前）
        String currentWord = extractCurrentWord(sql, cursorPos);
        String lowerWord = currentWord.toLowerCase();

        // 获取上下文提示类型
        CompletionContext ctx = analyzeContext(sql, cursorPos);

        switch (ctx) {
            case TABLE -> {
                // 建议表名
                items.addAll(getTableCompletions(request.getDatasourceId(), request.getDatabaseName(), lowerWord));
            }
            case COLUMN -> {
                // 建议列名 + 关键字
                items.addAll(getColumnCompletions(request.getDatasourceId(), request.getDatabaseName(), lowerWord));
                items.addAll(getKeywordCompletions(lowerWord));
            }
            default -> {
                // 通用：关键字
                items.addAll(getKeywordCompletions(lowerWord));
            }
        }

        result.setItems(items);
        return result;
    }

    private List<SqlCompleteResult.CompletionItem> getTableCompletions(String datasourceId, String databaseName, String prefix) {
        if (StrUtil.isBlank(datasourceId)) {
            return Collections.emptyList();
        }
        try {
            List<String> tables = datasourceApiClient.tables(datasourceId, databaseName);
            if (tables == null || tables.isEmpty()) {
                return Collections.emptyList();
            }
            return tables.stream()
                    .filter(t -> StrUtil.isBlank(prefix) || t.toLowerCase().contains(prefix))
                    .map(t -> new SqlCompleteResult.CompletionItem("TABLE", t, t, "表"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("获取表名补全失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SqlCompleteResult.CompletionItem> getColumnCompletions(String datasourceId, String databaseName, String prefix) {
        // 列补全基于已解析到的表名
        // 简化实现：返回当前数据源下表名字段（由前端在获得表名后请求字段）
        return Collections.emptyList();
    }

    private List<SqlCompleteResult.CompletionItem> getKeywordCompletions(String prefix) {
        return SQL_KEYWORDS.stream()
                .filter(k -> StrUtil.isBlank(prefix) || k.toLowerCase().startsWith(prefix))
                .map(k -> new SqlCompleteResult.CompletionItem("KEYWORD", k, k, "关键字"))
                .collect(Collectors.toList());
    }

    // ========================= SQL 格式化 =========================

    /**
     * 格式化 SQL（使用 JSQLParser 的标准输出）。
     */
    public String format(String sql) {
        if (StrUtil.isBlank(sql)) {
            return sql;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            return stmt.toString();
        } catch (Exception e) {
            log.warn("SQL 格式化失败，返回原始 SQL: {}", e.getMessage());
            return sql;
        }
    }

    // ========================= SQL 解析 =========================

    /**
     * 解析 SQL，提取涉及的表名和列引用。
     */
    public SqlParseResult parse(String sql) {
        SqlParseResult result = new SqlParseResult();
        if (StrUtil.isBlank(sql)) {
            result.setTables(Collections.emptyList());
            result.setColumns(Collections.emptyList());
            return result;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            List<String> tables = tablesNamesFinder.getTableList(stmt);
            result.setTables(tables == null ? Collections.emptyList() : tables);

            // 列引用提取（仅 SELECT）
            List<String> columns = new ArrayList<>();
            if (stmt instanceof Select select) {
                // 简化处理：文本提取
                columns = extractColumnRefs(sql);
            }
            result.setColumns(columns);
        } catch (Exception e) {
            result.setTables(Collections.emptyList());
            result.setColumns(Collections.emptyList());
            result.setParseError(e.getMessage());
        }
        return result;
    }

    private List<String> extractColumnRefs(String sql) {
        // 使用正则提取 SELECT ... FROM 之间的列引用
        Set<String> cols = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\bSELECT\\s+(.*?)\\s+FROM", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
        ).matcher(sql);
        if (m.find()) {
            String selectPart = m.group(1);
            for (String part : selectPart.split(",")) {
                part = part.trim();
                // 取别名或原始列名
                String col = part;
                int asIdx = part.toUpperCase().lastIndexOf(" AS ");
                if (asIdx > 0) {
                    col = part.substring(0, asIdx).trim();
                }
                int dotIdx = col.lastIndexOf('.');
                if (dotIdx >= 0) {
                    col = col.substring(dotIdx + 1);
                }
                if (!col.isEmpty() && !col.equals("*")) {
                    cols.add(col);
                }
            }
        }
        return new ArrayList<>(cols);
    }

    // ========================= 元数据提示 =========================

    /**
     * 获取指定数据源的表和列结构（用于编辑器侧边栏）。
     */
    public Map<String, Object> getSchemaHints(String datasourceId, String databaseName) {
        Map<String, Object> result = new HashMap<>();
        if (StrUtil.isBlank(datasourceId)) {
            result.put("tables", Collections.emptyList());
            return result;
        }
        try {
            List<String> tableNames = datasourceApiClient.tables(datasourceId, databaseName);
            if (tableNames == null) {
                result.put("tables", Collections.emptyList());
                return result;
            }
            List<Map<String, Object>> tables = new ArrayList<>();
            for (String tableName : tableNames) {
                Map<String, Object> tableInfo = new LinkedHashMap<>();
                tableInfo.put("name", tableName);
                try {
                    List<ColumnInfo> columns = datasourceApiClient.columnInfos(datasourceId, databaseName, tableName);
                    tableInfo.put("columns", columns != null ? columns.stream()
                            .map(c -> Map.of("name", c.getColumnName(), "type", c.getColumnType()))
                            .collect(Collectors.toList()) : Collections.emptyList());
                } catch (Exception e) {
                    tableInfo.put("columns", Collections.emptyList());
                }
                tables.add(tableInfo);
            }
            result.put("tables", tables);
        } catch (Exception e) {
            log.debug("获取 schema 信息失败: {}", e.getMessage());
            result.put("tables", Collections.emptyList());
        }
        return result;
    }

    // ========================= 工具方法 =========================

    private String extractCurrentWord(String sql, int cursorPos) {
        StringBuilder sb = new StringBuilder();
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                sb.insert(0, c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private CompletionContext analyzeContext(String sql, int cursorPos) {
        // 取光标前的子串
        String before = sql.substring(0, cursorPos).trim().toUpperCase();
        if (before.isEmpty()) {
            return CompletionContext.KEYWORD;
        }
        // 检查是否紧跟在 FROM/JOIN/INTO/UPDATE TABLE 后面（期待表名）
        if (before.matches(".*\\b(FROM|JOIN|INTO|UPDATE|TABLE|TRUNCATE)\\s+\\S*$")) {
            return CompletionContext.TABLE;
        }
        // 检查是否在 SELECT 后或 WHERE 后（期待列名/关键字）
        if (before.matches(".*\\b(SELECT|WHERE|SET|ON|HAVING)\\s+\\S*$")) {
            return CompletionContext.COLUMN;
        }
        return CompletionContext.KEYWORD;
    }

    private enum CompletionContext {
        KEYWORD, TABLE, COLUMN
    }
}
