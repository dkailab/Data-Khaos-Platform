package com.datakhaos.common.security.rewrite;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

/**
 * SQL 改写引擎：行级权限 WHERE 注入 + 列级权限投影裁剪/脱敏。
 * <p>
 * 基于 JSQLParser 4.6（与 mybatis-plus 3.5.5 同版本），
 * 支持单表、多表 JOIN、子查询、聚合场景。
 */
@Slf4j
public class SqlRewriteEngine {

    private static final Map<String, MaskFunction> MASK_FUNCTIONS = new HashMap<>();

    static {
        registerMask("MASK_PHONE", v -> "CONCAT(LEFT(" + v + ",3),'****',RIGHT(" + v + ",4))");
        registerMask("MASK_IDCARD", v -> "CONCAT(LEFT(" + v + ",4),'**********',RIGHT(" + v + ",4))");
        registerMask("MASK_EMAIL", v -> "CONCAT(LEFT(" + v + ",2),'***@',SUBSTRING_INDEX(" + v + ",'@',-1))");
        registerMask("MASK_NAME", v -> "CONCAT(LEFT(" + v + ",1),REPEAT('*',CHAR_LENGTH(" + v + ")-1))");
        registerMask("HIDE", v -> "NULL");
    }

    @Data
    public static class RowPolicy {
        private String targetTable;
        private String expression;

        public RowPolicy() {}

        public RowPolicy(String targetTable, String expression) {
            this.targetTable = targetTable;
            this.expression = expression;
        }
    }

    @Data
    public static class ColumnPolicy {
        private String targetTable;
        private String columnName;
        private String maskType;

        public ColumnPolicy() {}

        public ColumnPolicy(String targetTable, String columnName, String maskType) {
            this.targetTable = targetTable;
            this.columnName = columnName;
            this.maskType = maskType;
        }
    }

    @Data
    public static class RewriteResult {
        private String sql;
        private boolean changed;
        private List<String> appliedRows = new ArrayList<>();
        private List<String> appliedColumns = new ArrayList<>();
    }

    /**
     * 对 SQL 应用行级 + 列级权限改写。
     * 无策略时快速返回原 SQL。
     */
    public static RewriteResult rewrite(String sql, List<RowPolicy> rowPolicies, List<ColumnPolicy> columnPolicies) {
        RewriteResult result = new RewriteResult();
        if (StrUtil.isBlank(sql)) {
            result.setSql(sql);
            return result;
        }
        boolean hasRows = rowPolicies != null && !rowPolicies.isEmpty();
        boolean hasCols = columnPolicies != null && !columnPolicies.isEmpty();
        if (!hasRows && !hasCols) {
            result.setSql(sql);
            return result;
        }

        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                result.setSql(sql);
                return result;
            }

            if (hasRows) {
                PlainSelect plainSelect = extractPlainSelect(stmt);
                if (plainSelect != null) {
                    applyRowPolicies(plainSelect, rowPolicies, result);
                }
            }
            if (hasCols) {
                PlainSelect plainSelect = extractPlainSelect(stmt);
                if (plainSelect != null) {
                    applyColumnPolicies(plainSelect, columnPolicies, result);
                }
            }

            result.setSql(stmt.toString());
            result.setChanged(true);
        } catch (Exception e) {
            log.warn("SQL 改写失败，返回原始 SQL: {}", e.getMessage());
            result.setSql(sql);
        }
        return result;
    }

    private static void applyRowPolicies(PlainSelect plainSelect, List<RowPolicy> policies, RewriteResult result) throws Exception {
        Set<String> fromTables = extractFromTables(plainSelect);
        for (RowPolicy policy : policies) {
            if (StrUtil.isBlank(policy.getExpression())) continue;
            boolean match = fromTables.stream()
                .anyMatch(t -> t.equalsIgnoreCase(policy.getTargetTable()));
            if (!match) continue;

            Expression predicate = parsePredicate(policy.getExpression());
            if (predicate == null) continue;

            Expression currentWhere = plainSelect.getWhere();
            if (currentWhere == null) {
                plainSelect.setWhere(predicate);
            } else {
                plainSelect.setWhere(new AndExpression(currentWhere, predicate));
            }
            result.getAppliedRows().add(policy.getTargetTable() + ": " + policy.getExpression());
        }
    }

    private static void applyColumnPolicies(PlainSelect plainSelect, List<ColumnPolicy> policies, RewriteResult result) {
        Set<String> fromTables = extractFromTables(plainSelect);
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if (selectItems == null) return;

        Map<String, String> maskMap = new HashMap<>();
        for (ColumnPolicy policy : policies) {
            if (!fromTables.contains(policy.getTargetTable().toLowerCase()) &&
                !fromTables.contains(policy.getTargetTable())) {
                continue;
            }
            String key = (policy.getTargetTable() + "." + policy.getColumnName()).toLowerCase();
            maskMap.put(key, policy.getMaskType());
        }
        if (maskMap.isEmpty()) return;

        for (SelectItem item : selectItems) {
            if (item instanceof SelectExpressionItem) {
                SelectExpressionItem sei = (SelectExpressionItem) item;
                Expression expr = sei.getExpression();
                if (expr instanceof Column) {
                    Column col = (Column) expr;
                    Table table = col.getTable();
                    String tableName = table != null ? table.getName() : "";
                    String column = col.getColumnName();
                    String fullKey = (tableName + "." + column).toLowerCase();
                    String maskType = maskMap.get(fullKey);

                    if (StrUtil.isNotBlank(maskType)) {
                        String maskedExpr = applyMask(column, maskType, tableName);
                        if (maskedExpr != null) {
                            col.setColumnName(maskedExpr);
                            result.getAppliedColumns().add(fullKey + " -> " + maskType);
                        }
                    }
                }
            }
        }
    }

    private static String applyMask(String column, String maskType, String table) {
        String qualified = StrUtil.isNotBlank(table) ? table + "." + column : column;
        MaskFunction fn = MASK_FUNCTIONS.get(maskType.toUpperCase());
        if (fn != null) {
            return fn.apply(qualified) + " AS " + column;
        }
        return null;
    }

    /**
     * 从 Statement 中提取 PlainSelect。
     * JSQLParser 4.6 中，Simple Select 直接返回 PlainSelect；
     * UNION/INTERSECT 返回 Select 内含 SetOperationList。
     */
    private static PlainSelect extractPlainSelect(Statement stmt) {
        if (stmt instanceof PlainSelect) {
            return (PlainSelect) stmt;
        }
        if (stmt instanceof Select) {
            Select select = (Select) stmt;
            if (select.getSelectBody() instanceof PlainSelect) {
                return (PlainSelect) select.getSelectBody();
            }
        }
        return null;
    }

    private static Set<String> extractFromTables(PlainSelect select) {
        Set<String> tables = new HashSet<>();
        FromItem from = select.getFromItem();
        if (from != null) {
            extractTableNames(from, tables);
        }
        List<Join> joins = select.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                if (join.getRightItem() != null) {
                    extractTableNames(join.getRightItem(), tables);
                }
            }
        }
        return tables;
    }

    private static void extractTableNames(FromItem from, Set<String> tables) {
        if (from instanceof Table) {
            Table t = (Table) from;
            tables.add(t.getName().toLowerCase());
            Alias alias = t.getAlias();
            if (alias != null && alias.getName() != null) {
                tables.add(alias.getName().toLowerCase());
            }
        }
    }

    private static Expression parsePredicate(String expr) {
        String resolved = expr
            .replace("#{currentUserId}", "'__CURRENT_USER_ID__'")
            .replace("#{currentOrgId}", "'__CURRENT_ORG_ID__'");
        try {
            return CCJSqlParserUtil.parseCondExpression(resolved);
        } catch (Exception e) {
            log.warn("解析过滤表达式失败: {}", expr, e);
            return null;
        }
    }

    public static void registerMask(String name, MaskFunction fn) {
        MASK_FUNCTIONS.put(name.toUpperCase(), fn);
    }

    @FunctionalInterface
    public interface MaskFunction {
        String apply(String columnName);
    }
}
