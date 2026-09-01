package com.datakhaos.query.service;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.SqlCompleteRequest;
import com.datakhaos.query.dto.SqlCompleteResult;
import com.datakhaos.query.dto.SqlDiagnoseResult;
import com.datakhaos.query.dto.SqlParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
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

    private static final List<String> SQL_FUNCTIONS = Arrays.asList(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "IFNULL", "NULLIF",
            "CONCAT", "LOWER", "UPPER", "TRIM", "LENGTH", "SUBSTRING", "REPLACE",
            "DATE_FORMAT", "DATE_ADD", "DATE_SUB", "NOW", "CURDATE", "CURTIME",
            "CAST", "ROUND", "ABS", "FLOOR", "CEILING"
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

        String before = sql.substring(0, cursorPos);
        String currentWord = extractCurrentWord(sql, cursorPos);
        String lowerWord = currentWord.toLowerCase();

        // 光标处作用域内的表（含别名映射：别名/表名 -> 真实表名）。
        // 用完整 SQL 解析，保证 SELECT 子句补全时也能看到其后方的 FROM/JOIN 表。
        Map<String, String> scope = analyzeTableScope(sql);

        // 是否带表限定符（如 t.xx ），并拆出限定符与列前缀
        int dotIdx = currentWord.lastIndexOf('.');
        boolean qualified = dotIdx >= 0;
        String qualifier = qualified ? currentWord.substring(0, dotIdx) : null;
        String columnPrefix = qualified ? currentWord.substring(dotIdx + 1) : currentWord;

        CompletionContext ctx = analyzeContext(before, qualified);

        switch (ctx) {
            case TABLE -> {
                // 建议表名
                items.addAll(getTableCompletions(request.getDatasourceId(), request.getDatabaseName(), lowerWord));
            }
            case COLUMN -> {
                // 智能列补全：基于作用域表 + 限定符 + 函数 + 关键字
                items.addAll(getColumnCompletions(request.getDatasourceId(), request.getDatabaseName(), scope, qualifier, columnPrefix));
                items.addAll(getFunctionCompletions(columnPrefix));
                items.addAll(getKeywordCompletions(columnPrefix));
            }
            default -> {
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

    /**
     * 智能列补全：根据作用域内的表（含别名）返回列建议。
     * <p>
     * - 带限定符（如 {@code t.}）：仅补全限定符对应表的列；
     * - 无限定符：补全作用域内所有表的列（去重）；
     * - 无作用域表时返回空，由上层回落到函数/关键字提示。
     */
    private List<SqlCompleteResult.CompletionItem> getColumnCompletions(String datasourceId, String databaseName,
                                                                        Map<String, String> scope, String qualifier, String prefix) {
        if (StrUtil.isBlank(datasourceId)) {
            return Collections.emptyList();
        }
        List<String> targetTables = new ArrayList<>();
        if (StrUtil.isNotBlank(qualifier)) {
            String resolved = scope.get(qualifier);
            targetTables.add(resolved != null ? resolved : qualifier);
        } else {
            if (scope.isEmpty()) {
                return Collections.emptyList();
            }
            targetTables.addAll(scope.values());
        }
        if (targetTables.isEmpty()) {
            return Collections.emptyList();
        }

        String p = prefix == null ? "" : prefix.toLowerCase();
        Set<String> seen = new HashSet<>();
        List<SqlCompleteResult.CompletionItem> items = new ArrayList<>();
        for (String tableName : targetTables) {
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            try {
                List<ColumnInfo> cols = datasourceApiClient.columnInfos(datasourceId, databaseName, tableName);
                if (cols == null) {
                    continue;
                }
                for (ColumnInfo c : cols) {
                    String cn = c.getColumnName();
                    if (cn == null) {
                        continue;
                    }
                    if (!p.isEmpty() && !cn.toLowerCase().contains(p)) {
                        continue;
                    }
                    if (seen.add(cn)) {
                        String type = c.getColumnType();
                        String detail = tableName + (StrUtil.isBlank(type) ? "" : " · " + type);
                        items.add(new SqlCompleteResult.CompletionItem("COLUMN", cn, cn, detail));
                    }
                }
            } catch (Exception e) {
                log.debug("获取列补全失败 {}: {}", tableName, e.getMessage());
            }
        }
        return items;
    }

    private List<SqlCompleteResult.CompletionItem> getFunctionCompletions(String prefix) {
        return SQL_FUNCTIONS.stream()
                .filter(f -> StrUtil.isBlank(prefix) || f.toLowerCase().startsWith(prefix.toLowerCase()))
                .map(f -> new SqlCompleteResult.CompletionItem("FUNCTION", f, f + "(", "函数"))
                .collect(Collectors.toList());
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

    // ========================= SQL 健康诊断 =========================

    /**
     * 对 SQL 做健康诊断：select *、缺 WHERE、全表扫描、JOIN 无 ON、隐式类型转换。
     * <p>
     * 前四类基于 JSQLParser 语法结构判断；隐式类型转换在提供数据源时按列类型比对。
     */
    public SqlDiagnoseResult diagnose(String sql, String datasourceId, String databaseName) {
        SqlDiagnoseResult result = new SqlDiagnoseResult();
        if (StrUtil.isBlank(sql)) {
            result.setHealthy(true);
            return result;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            Map<String, String> columnTypes = loadColumnTypes(datasourceId, databaseName, extractTables(stmt));
            Set<String> seen = new HashSet<>();

            if (stmt instanceof Select select && select.getSelectBody() != null) {
                walkSelectBody(select.getSelectBody(), result, columnTypes, seen);
            }
        } catch (Exception e) {
            log.debug("SQL 诊断解析失败，回退到文本检测: {}", e.getMessage());
            diagnoseByRegex(sql, result);
        }
        result.setHealthy(result.getIssues().stream().noneMatch(i -> "error".equals(i.getSeverity())));
        return result;
    }

    /** 递归遍历 SELECT 结构执行检查 */
    private void walkSelectBody(SelectBody body, SqlDiagnoseResult result, Map<String, String> columnTypes, Set<String> seen) {
        if (body instanceof PlainSelect ps) {
            checkPlainSelect(ps, result, columnTypes, seen);
        } else if (body instanceof SetOperationList sol && sol.getSelects() != null) {
            for (SelectBody sb : sol.getSelects()) {
                walkSelectBody(sb, result, columnTypes, seen);
            }
        }
    }

    private void checkPlainSelect(PlainSelect ps, SqlDiagnoseResult result, Map<String, String> columnTypes, Set<String> seen) {
        List<SelectItem> items = ps.getSelectItems();
        List<Join> joins = ps.getJoins();
        boolean hasRealTable = ps.getFromItem() instanceof Table;

        // 0) 将表别名注册到列类型映射，使 a.col / c.col 也能命中类型判断
        registerAliasColumnTypes(ps, columnTypes);

        // 1) SELECT *
        boolean selectAll = false;
        if (items != null) {
            for (SelectItem si : items) {
                if (si instanceof AllColumns || si instanceof AllTableColumns) {
                    selectAll = true;
                    break;
                }
            }
        }
        if (selectAll) {
            addIssue(result, seen, "error", "SELECT_STAR", "查询使用 SELECT *，会返回全部列、浪费 IO 且难以阅读",
                    "请显式列出所需字段，避免使用 *");
        }

        // 2) JOIN 无 ON + 递归收集表
        if (joins != null) {
            for (Join join : joins) {
                FromItem right = join.getRightItem();
                if (right instanceof Table) {
                    hasRealTable = true;
                }
                if (right instanceof Table) {
                    java.util.Collection<Expression> onExprs = join.getOnExpressions();
                    java.util.Collection<Column> usingCols = join.getUsingColumns();
                    boolean hasOn = (onExprs != null && !onExprs.isEmpty())
                            || (usingCols != null && !usingCols.isEmpty());
                    if (!hasOn) {
                        addIssue(result, seen, "warning", "JOIN_NO_ON",
                                "JOIN 缺少 ON 关联条件，可能产生笛卡尔积",
                                "请为 JOIN 补全 ON 关联条件（等值连接）");
                    } else {
                        for (Expression on : onExprs) {
                            checkImplicitConversions(on == null ? "" : on.toString(), columnTypes, result, seen);
                        }
                    }
                }
                if (right instanceof SubSelect sub && sub.getSelectBody() != null) {
                    walkSelectBody(sub.getSelectBody(), result, columnTypes, seen);
                }
            }
        }

        // 3) 缺 WHERE / 全表扫描（仅针对真实表的简单查询，聚合查询跳过）
        boolean hasWhere = ps.getWhere() != null;
        boolean aggregate = isAggregate(items);
        if (hasRealTable && !hasWhere && !aggregate) {
            addIssue(result, seen, "warning", "NO_WHERE",
                    "查询缺少 WHERE 条件，可能扫描全表，耗时且不稳定",
                    "请添加 WHERE 过滤条件");
            if (ps.getLimit() == null) {
                addIssue(result, seen, "error", "FULL_TABLE_SCAN",
                        "该查询无 WHERE 且无 LIMIT，将对整表进行全表扫描",
                        "请补充 WHERE 过滤，或使用 LIMIT 限制返回行数");
            }
        }

        // 4) WHERE 子句隐式类型转换
        if (ps.getWhere() != null) {
            checkImplicitConversions(ps.getWhere().toString(), columnTypes, result, seen);
        }

        // 递归：从项中的子查询
        if (ps.getFromItem() instanceof SubSelect sub && sub.getSelectBody() != null) {
            walkSelectBody(sub.getSelectBody(), result, columnTypes, seen);
        }
    }

    /** 聚合判断：SELECT 中是否有 COUNT/SUM/AVG/MAX/MIN 等聚合函数 */
    private boolean isAggregate(List<SelectItem> items) {
        if (items == null) return false;
        for (SelectItem si : items) {
            String s = si.toString().toUpperCase();
            if (s.matches("(?s).*\\b(COUNT|SUM|AVG|MAX|MIN|GROUP_CONCAT)\\s*\\(.*")) {
                return true;
            }
        }
        return false;
    }

    /** 将 FROM/JOIN 的表别名注册到列类型映射，使 a.col 也能命中类型判断 */
    private void registerAliasColumnTypes(PlainSelect ps, Map<String, String> columnTypes) {
        if (columnTypes == null || columnTypes.isEmpty()) {
            return;
        }
        List<Table> tables = new ArrayList<>();
        if (ps.getFromItem() instanceof Table t) {
            tables.add(t);
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getRightItem() instanceof Table t) {
                    tables.add(t);
                }
            }
        }
        for (Table t : tables) {
            String table = tablePart(t.getName());
            String alias = t.getAlias() == null ? null : t.getAlias().getName();
            if (StrUtil.isBlank(alias)) {
                continue;
            }
            // 把 表名.列名 复制为 别名.列名（先快照再写，避免 ConcurrentModificationException）
            String prefix = (table + ".").toLowerCase();
            List<Map.Entry<String, String>> snapshot = new ArrayList<>(columnTypes.entrySet());
            for (Map.Entry<String, String> e : snapshot) {
                if (e.getKey().startsWith(prefix)) {
                    columnTypes.putIfAbsent((alias + "." + e.getKey().substring(prefix.length())).toLowerCase(), e.getValue());
                }
            }
        }
    }

    /** 加载涉及表的 列名引用 -> 类型，用于隐式类型转换判断 */
    private Map<String, String> loadColumnTypes(String datasourceId, String databaseName, List<String> tables) {
        Map<String, String> types = new HashMap<>();
        if (StrUtil.isBlank(datasourceId) || tables == null) {
            return types;
        }
        for (String t : tables) {
            String tb = tablePart(t);
            try {
                List<ColumnInfo> cols = datasourceApiClient.columnInfos(datasourceId, databaseName, tb);
                for (ColumnInfo c : cols) {
                    String type = StrUtil.nullToDefault(c.getColumnType(), "").toUpperCase();
                    types.put((tb + "." + c.getColumnName()).toLowerCase(), type);
                    // 无歧义时也挂到裸列名
                    types.putIfAbsent(c.getColumnName().toLowerCase(), type);
                }
            } catch (Exception ignored) {
            }
        }
        return types;
    }

    /** 隐式类型转换检测：join on / where 中不同类型的列比较 */
    private void checkImplicitConversions(String expr, Map<String, String> types,
                                          SqlDiagnoseResult result, Set<String> seen) {
        if (StrUtil.isBlank(expr) || types.isEmpty()) {
            return;
        }
        // a) 列 = 列（JOIN 等值），两类不同 -> 隐式转换
        java.util.regex.Matcher cc = java.util.regex.Pattern.compile(
                "([\\w$]+)\\.([\\w$]+)\\s*=\\s*([\\w$]+)\\.([\\w$]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(expr);
        while (cc.find()) {
            String t1 = category(types.get((cc.group(1) + "." + cc.group(2)).toLowerCase()));
            String t2 = category(types.get((cc.group(3) + "." + cc.group(4)).toLowerCase()));
            if (bothKnown(t1, t2) && !t1.equals(t2)) {
                addIssue(result, seen, "error", "IMPLICIT_CONVERSION",
                        "条件 " + cc.group(1) + "." + cc.group(2) + " = " + cc.group(3) + "." + cc.group(4)
                                + " 两侧列类型不一致（" + t1 + " vs " + t2 + "），会触发隐式类型转换，索引可能失效",
                        "请将两侧列转换为相同类型（如统一用 CAST）再比较");
            }
        }
        // b) 数值/日期列 = 字符串字面量
        java.util.regex.Matcher nls = java.util.regex.Pattern.compile(
                "([\\w$]+)\\.([\\w$]+)\\s*(=|<>|<=|>=|<|>)\\s*'([^']*)'", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(expr);
        while (nls.find()) {
            String cat = category(types.get((nls.group(1) + "." + nls.group(2)).toLowerCase()));
            if ("NUM".equals(cat) && nls.group(4).matches("\\d+")) {
                addIssue(result, seen, "warning", "IMPLICIT_CONVERSION",
                        "数值列 " + nls.group(2) + " 与字符串字面量 '" + nls.group(4) + "' 比较，会触发隐式转换",
                        "请改用数值字面量： " + nls.group(2) + " " + nls.group(3) + " " + nls.group(4));
            }
        }
    }

    private boolean bothKnown(String t1, String t2) {
        return t1 != null && t2 != null && !"UNKNOWN".equals(t1) && !"UNKNOWN".equals(t2);
    }

    /** 类型归一化分类 */
    private String category(String type) {
        if (StrUtil.isBlank(type)) {
            return "UNKNOWN";
        }
        String t = type.trim().toUpperCase();
        if (t.startsWith("INT") || t.startsWith("BIGINT") || t.startsWith("SMALLINT")
                || t.startsWith("TINYINT") || t.startsWith("MEDIUMINT")
                || t.startsWith("DEC") || t.startsWith("NUMERIC") || t.startsWith("FLOAT")
                || t.startsWith("DOUBLE") || t.startsWith("REAL")) {
            return "NUM";
        }
        if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.startsWith("TEXT")) {
            return "STR";
        }
        if (t.startsWith("DATE") || t.startsWith("TIME") || t.startsWith("YEAR")) {
            return "DATE";
        }
        if (t.startsWith("BINARY") || t.startsWith("BLOB")) {
            return "BIN";
        }
        return "UNKNOWN";
    }

    private String tablePart(String t) {
        if (t == null) return t;
        int i = t.indexOf('.');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    private List<String> extractTables(Statement stmt) {
        try {
            TablesNamesFinder f = new TablesNamesFinder();
            List<String> t = f.getTableList(stmt);
            return t == null ? Collections.emptyList() : t;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void addIssue(SqlDiagnoseResult result, Set<String> seen, String severity,
                          String rule, String message, String suggestion) {
        String key = rule + "|" + message;
        if (!seen.add(key)) {
            return;
        }
        result.getIssues().add(new SqlDiagnoseResult.DiagnosisIssue(severity, rule, message, suggestion));
    }

    /** SQL 无法被 JSQLParser 解析时的文本级兜底诊断 */
    private void diagnoseByRegex(String sql, SqlDiagnoseResult result) {
        Set<String> seen = new HashSet<>();
        boolean fromPresent = java.util.regex.Pattern.compile("(?i)\\bfrom\\b").matcher(sql).find();
        boolean wherePresent = java.util.regex.Pattern.compile("(?i)\\bwhere\\b").matcher(sql).find();
        boolean limitPresent = java.util.regex.Pattern.compile("(?i)\\blimit\\b").matcher(sql).find();
        if (fromPresent && !wherePresent) {
            addIssue(result, seen, "warning", "NO_WHERE", "查询缺少 WHERE 条件", "请添加 WHERE 过滤条件");
            if (!limitPresent) {
                addIssue(result, seen, "error", "FULL_TABLE_SCAN", "查询无 WHERE 且无 LIMIT，会全表扫描", "请补充 WHERE 或 LIMIT");
            }
        }
    }

    // ========================= 执行计划 EXPLAIN =========================

    /**
     * 对目标 SQL 执行 EXPLAIN 获取执行计划（访问类型 / 扫描行数 / 索引）。
     */
    public QueryResult explain(String datasourceId, String databaseName, String sql) {
        if (StrUtil.isBlank(datasourceId)) {
            throw new BusinessException("数据源ID不能为空");
        }
        if (StrUtil.isBlank(sql)) {
            throw new BusinessException("SQL 不能为空");
        }
        String clean = sql.trim().replaceFirst(";\\s*$", "");
        String explainSql = "EXPLAIN " + clean;
        R<QueryResult> resp;
        try {
            resp = datasourceApiClient.executeRaw(datasourceId, explainSql);
        } catch (Exception e) {
            throw new BusinessException("获取执行计划失败: " + e.getMessage());
        }
        if (resp == null || resp.getCode() != 0) {
            throw new BusinessException(resp == null ? "获取执行计划失败" : resp.getMsg());
        }
        return resp.getData();
    }

    // ========================= 元数据提示 =========================

    /**
     * 获取指定数据源的表列表（懒加载：仅返回表名，列在展开表时按需获取）。
     */
    public Map<String, Object> getSchemaHints(String datasourceId, String databaseName) {
        Map<String, Object> result = new HashMap<>();
        if (StrUtil.isBlank(datasourceId)) {
            result.put("tables", Collections.emptyList());
            return result;
        }
        try {
            List<String> tableNames = datasourceApiClient.tables(datasourceId, databaseName);
            result.put("tables", tableNames == null ? Collections.emptyList() : tableNames);
        } catch (Exception e) {
            log.debug("获取 schema 信息失败: {}", e.getMessage());
            result.put("tables", Collections.emptyList());
        }
        return result;
    }

    /**
     * 懒加载获取单表字段（点击表节点时调用，避免一次全量拉取产生 N+1）。
     */
    public List<ColumnInfo> getTableColumns(String datasourceId, String databaseName, String tableName) {
        if (StrUtil.isBlank(datasourceId) || StrUtil.isBlank(tableName)) {
            return Collections.emptyList();
        }
        try {
            List<ColumnInfo> columns = datasourceApiClient.columnInfos(datasourceId, databaseName, tableName);
            return columns == null ? Collections.emptyList() : columns;
        } catch (Exception e) {
            log.debug("获取表字段失败 {}: {}", tableName, e.getMessage());
            return Collections.emptyList();
        }
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

    /**
     * 上下文分析：判断当前光标应提示表名、列名还是关键字。
     *
     * @param qualified 是否已输入表限定符（如 {@code t.}）
     */
    private CompletionContext analyzeContext(String before, boolean qualified) {
        String s = before == null ? "" : before.trim().toUpperCase();
        if (s.isEmpty()) {
            return CompletionContext.KEYWORD;
        }
        // 表名位置：FROM / JOIN / INTO / UPDATE TABLE / TRUNCATE 之后
        if (s.matches(".*\\b(FROM|JOIN|INTO|UPDATE|TABLE|TRUNCATE)\\s+\\S*$")) {
            return CompletionContext.TABLE;
        }
        // 限定符 t. 一定是列位置
        if (qualified) {
            return CompletionContext.COLUMN;
        }
        // 列位置：列关键词、逗号、括号、比较符、运算符之后
        if (s.matches(".*\\b(SELECT|WHERE|ON|HAVING|SET|AND|OR|BY|DISTINCT|RETURNING)\\s+\\S*$")
                || s.endsWith(",") || s.endsWith("(") || s.endsWith("*")
                || s.endsWith("=") || s.endsWith("<") || s.endsWith(">")) {
            return CompletionContext.COLUMN;
        }
        return CompletionContext.KEYWORD;
    }

    /**
     * 解析光标处 SQL 的作用域表（含别名），用于列补全。
     * <p>
     * 优先用 JSQLParser 解析；对不完整 SQL 回退到正则提取 FROM/JOIN 表。
     */
    private Map<String, String> analyzeTableScope(String before) {
        Map<String, String> scope = new LinkedHashMap<>();
        if (before == null || before.isBlank()) {
            return scope;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(before);
            collectFromStatement(stmt, scope);
        } catch (Exception e) {
            collectTablesByRegex(before, scope);
        }
        return scope;
    }

    private void collectFromStatement(Statement stmt, Map<String, String> scope) {
        if (stmt instanceof Select select && select.getSelectBody() != null) {
            collectSelectBody(select.getSelectBody(), scope);
        }
    }

    private void collectSelectBody(SelectBody body, Map<String, String> scope) {
        if (body instanceof PlainSelect ps) {
            collectFromItem(ps.getFromItem(), scope);
            if (ps.getJoins() != null) {
                for (Join join : ps.getJoins()) {
                    collectFromItem(join.getRightItem(), scope);
                }
            }
        }
        // SetOperationList（UNION 等）暂不处理
    }

    private void collectFromItem(FromItem item, Map<String, String> scope) {
        if (item == null) {
            return;
        }
        if (item instanceof Table table) {
            String name = table.getName();
            if (name == null || name.isBlank()) {
                return;
            }
            scope.putIfAbsent(name, name);
            if (table.getAlias() != null && StrUtil.isNotBlank(table.getAlias().getName())) {
                scope.putIfAbsent(table.getAlias().getName(), name);
            }
        } else if (item instanceof SubSelect subSelect && subSelect.getSelectBody() != null) {
            collectSelectBody(subSelect.getSelectBody(), scope);
        }
        // 其它（如括号表）暂不处理
    }

    private void collectTablesByRegex(String sql, Map<String, String> scope) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\b(?:FROM|JOIN)\\s+([\\w$#.\"'`\\[\\]]+)(?:\\s+(?:AS\\s+)?([\\w$]+))?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            String raw = m.group(1).replaceAll("[\"'`\\[\\]]", "");
            int dot = raw.lastIndexOf('.');
            String tableName = dot >= 0 ? raw.substring(dot + 1) : raw;
            scope.putIfAbsent(tableName, tableName);
            String alias = m.group(2);
            if (alias != null) {
                scope.putIfAbsent(alias, tableName);
            }
        }
    }

    private enum CompletionContext {
        KEYWORD, TABLE, COLUMN
    }
}
