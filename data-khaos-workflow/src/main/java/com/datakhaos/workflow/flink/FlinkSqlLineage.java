package com.datakhaos.workflow.flink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink SQL 表级血缘解析（不依赖 SQL 引擎）。
 *
 * <p>只解决一件事：从「一组 DDL + 一条 INSERT INTO ... SELECT」里找出
 * 目标表和它真正依赖的源表。解析容忍视图与 CTE，会把它们展开到物理表；
 * 解析不出来时返回空结果，绝不猜测。</p>
 *
 * <p>表名到元数据资产的映射不在这里做：DDL 的 {@code datakhaos.asset-id}
 * 只作为显式提示返回，是否采用由上层决定。</p>
 */
public final class FlinkSqlLineage {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\w.$-]+)");
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "(?is)^CREATE\\s+(?:TEMPORARY\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\w.$-]+)\\s+AS\\s+(.+)$");
    private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+INTO\\s+([`\"\\w.$-]+)");
    private static final Pattern OPTION = Pattern.compile("'([^']+)'\\s*=\\s*'([^']*)'");
    private static final Pattern CTE = Pattern.compile("(?is)(?:\\bWITH|,)\\s*([`\"\\w$]+)\\s+AS\\s*\\(");
    private static final Pattern FROM = Pattern.compile("(?i)\\bFROM\\b");
    private static final Pattern JOIN = Pattern.compile("(?i)\\bJOIN\\s+([`\"\\w.$-]+)");
    private static final Set<String> CLAUSES = Set.of("WHERE", "GROUP", "ORDER", "LIMIT", "ON", "JOIN", "LEFT",
            "RIGHT", "INNER", "FULL", "CROSS", "UNION", "HAVING", "WINDOW", "TABLESAMPLE", "FOR", "AS", "MATCH_RECOGNIZE");

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '`' || c == '"';
    }

    private FlinkSqlLineage() {
    }

    /**
     * @param sink              INSERT 目标逻辑表名
     * @param sinkConnector     目标 DDL 的 connector，未知为 null
     * @param sinkPhysicalName  目标物理表名（jdbc 的 database.table 或 kafka topic），未知为 null
     * @param sinkAssetHint     目标 DDL 里的 datakhaos.asset-id，未知为 null
     * @param sources           源表（已展开视图/CTE，去掉目标表本身）
     */
    public record Result(String sink, String sinkConnector, String sinkPhysicalName, String sinkAssetHint,
                         List<Source> sources) {
        public boolean empty() {
            return sink == null || sink.isBlank();
        }
    }

    public record Source(String name, String connector, String physicalName, String assetHint) {
    }

    private record TableDef(String name, String connector, String physicalName, String assetHint) {
    }

    /** 解析整段脚本；无法识别 INSERT 时返回空结果。 */
    public static Result parse(String script) {
        if (script == null || script.isBlank()) {
            return new Result(null, null, null, null, List.of());
        }
        List<String> statements;
        try {
            statements = FlinkRunSql.split(script);
        } catch (RuntimeException e) {
            return new Result(null, null, null, null, List.of());
        }
        Map<String, TableDef> definitions = new LinkedHashMap<>();
        Set<String> virtualNames = new LinkedHashSet<>();
        List<String> viewBodies = new ArrayList<>();
        String insert = null;

        for (String statement : statements) {
            Matcher table = CREATE_TABLE.matcher(statement);
            if (table.find()) {
                String name = clean(table.group(1));
                definitions.put(key(name), definition(name, statement));
                continue;
            }
            Matcher view = CREATE_VIEW.matcher(statement);
            if (view.find()) {
                virtualNames.add(key(clean(view.group(1))));
                viewBodies.add(view.group(2));
                continue;
            }
            if (INSERT.matcher(statement).find()) {
                insert = statement;
            }
        }
        if (insert == null) {
            return new Result(null, null, null, null, List.of());
        }

        // CTE 只存在于 INSERT / 视图体内，先收集名字再从源表中剔除。
        for (String body : viewBodies) {
            collectCtes(body, virtualNames);
        }
        collectCtes(insert, virtualNames);

        Matcher insertMatcher = INSERT.matcher(insert);
        if (!insertMatcher.find()) {
            return new Result(null, null, null, null, List.of());
        }
        String sinkName = clean(insertMatcher.group(1));
        TableDef sink = definitions.get(key(sinkName));

        Set<String> sourceNames = new LinkedHashSet<>();
        for (String body : viewBodies) {
            sourceNames.addAll(fromRefs(body));
        }
        sourceNames.addAll(fromRefs(insert));

        List<Source> sources = new ArrayList<>();
        for (String name : sourceNames) {
            String k = key(name);
            if (k.equals(key(sinkName)) || virtualNames.contains(k)) {
                continue;
            }
            TableDef def = definitions.get(k);
            sources.add(def == null
                    ? new Source(name, null, null, null)
                    : new Source(def.name(), def.connector(), def.physicalName(), def.assetHint()));
        }
        return new Result(sinkName,
                sink == null ? null : sink.connector(),
                sink == null ? null : sink.physicalName(),
                sink == null ? null : sink.assetHint(),
                sources);
    }

    private static TableDef definition(String name, String statement) {
        Map<String, String> options = new LinkedHashMap<>();
        Matcher option = OPTION.matcher(statement);
        while (option.find()) {
            options.putIfAbsent(option.group(1).strip().toLowerCase(Locale.ROOT), option.group(2).strip());
        }
        String connector = options.get("connector");
        String table = options.get("table-name");
        String database = firstNonBlank(options.get("database-name"), options.get("db"));
        String topic = options.get("topic");
        String physical = table != null && !table.isBlank()
                ? (database == null ? table : database + "." + table)
                : topic;
        String hint = firstNonBlank(options.get("datakhaos.asset-id"), options.get("asset-id"),
                options.get("asset.id"));
        return new TableDef(name, connector, physical, hint);
    }

    /** 收集 FROM 后的表引用，支持 `FROM a, b` 与 `JOIN c`；子查询由内层 FROM 自行收集。 */
    private static List<String> fromRefs(String sql) {
        String masked = maskLiterals(sql);
        List<String> refs = new ArrayList<>();
        Matcher from = FROM.matcher(masked);
        while (from.find()) {
            if (insideFunctionCall(masked, from.start())) {
                continue;
            }
            int index = from.end();
            while (true) {
                index = skipSpaces(masked, index);
                if (index >= masked.length() || masked.charAt(index) == '(') {
                    break;
                }
                String name = readIdentifier(masked, index);
                if (name == null) {
                    break;
                }
                refs.add(clean(name));
                index = skipSpaces(masked, index + name.length());
                // 跳过表别名（如 `FROM orders o, dim d`），否则逗号连接会被漏掉。
                if (index < masked.length() && isIdentifierStart(masked.charAt(index))) {
                    String alias = readIdentifier(masked, index);
                    if (alias != null && !CLAUSES.contains(alias.toUpperCase(Locale.ROOT))) {
                        index = skipSpaces(masked, index + alias.length());
                    }
                }
                if (index < masked.length() && masked.charAt(index) == ',') {
                    index++;
                    continue;
                }
                break;
            }
        }
        Matcher join = JOIN.matcher(masked);
        while (join.find()) {
            refs.add(clean(join.group(1)));
        }
        return refs;
    }

    /**
     * 判断 FROM 是否位于函数调用内部（如 {@code EXTRACT(YEAR FROM ts)}、{@code TRIM(... FROM col)}），
     * 这类 FROM 后面不是表名。子查询的 {@code FROM (SELECT ...)} 不算函数调用。
     */
    private static boolean insideFunctionCall(String text, int position) {
        int depth = 0;
        for (int i = position - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    int j = i - 1;
                    while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
                        j--;
                    }
                    int end = j + 1;
                    while (j >= 0 && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                        j--;
                    }
                    String word = text.substring(j + 1, end).toUpperCase(Locale.ROOT);
                    return !(word.isEmpty() || word.equals("SELECT") || word.equals("FROM") || word.equals("IN")
                            || word.equals("EXISTS") || word.equals("JOIN") || word.equals("AS")
                            || word.equals("UNION"));
                }
                depth--;
            }
        }
        return false;
    }

    private static void collectCtes(String sql, Set<String> names) {
        Matcher matcher = CTE.matcher(maskLiterals(sql));
        while (matcher.find()) {
            String name = clean(matcher.group(1));
            if (!name.isBlank()) {
                names.add(key(name));
            }
        }
    }

    /** 把单引号字符串替换成空格，避免字符串里的 from/join 被当成表名。 */
    private static String maskLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote == 0) {
                if (c == '\'') {
                    quote = c;
                    out.append(' ');
                } else {
                    out.append(c);
                }
            } else if (c == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append("  ");
                    i++;
                } else {
                    quote = 0;
                    out.append(' ');
                }
            } else {
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static int skipSpaces(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String readIdentifier(String text, int start) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-' || c == '.'
                    || c == '`' || c == '"') {
                i++;
            } else {
                break;
            }
        }
        return i == start ? null : text.substring(start, i);
    }

    private static String clean(String identifier) {
        return identifier == null ? null : identifier.replace("`", "").replace("\"", "").strip();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
