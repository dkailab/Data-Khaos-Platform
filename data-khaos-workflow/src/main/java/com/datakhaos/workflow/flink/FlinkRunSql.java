package com.datakhaos.workflow.flink;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record FlinkRunSql(List<String> setup, String insert) {
    private static final Pattern TEMPORARY = Pattern.compile("(?is)^CREATE\\s+TEMPORARY\\s+(TABLE|VIEW|FUNCTION)\\s+.+$");
    private static final Pattern SETTING = Pattern.compile(
            "(?is)^SET\\s+'(execution\\.runtime-mode|parallelism\\.default|table\\.local-time-zone|execution\\.checkpointing\\.interval)'\\s*=\\s*'[^']+'$");
    private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+INTO\\s+.+$");

    public static FlinkRunSql parse(String script) {
        List<String> statements = split(script);
        if (statements.isEmpty() || statements.size() > 30 || !INSERT.matcher(statements.get(statements.size() - 1)).matches()) {
            throw invalid("运行脚本最多 30 条语句，最后一条须为 INSERT INTO");
        }
        List<String> setup = statements.subList(0, statements.size() - 1);
        validateSetup(setup);
        return new FlinkRunSql(List.copyOf(setup), statements.get(statements.size() - 1));
    }

    public static Selection parseSelection(String script) {
        if (script == null || script.isBlank() || script.length() > 131072) {
            throw invalid("请选择有效的 SQL，最多 131072 个字符");
        }
        List<String> statements = split(script);
        if (statements.isEmpty() || statements.size() > 30) {
            throw invalid("选中调试最多支持 30 条语句");
        }
        String query = statements.get(statements.size() - 1);
        if (!query.matches("(?is)^SELECT\\s+.+$")) {
            throw invalid("请选择完整的只读 SELECT，可同时选中前置临时表或视图");
        }
        List<String> setup = statements.subList(0, statements.size() - 1);
        for (String statement : setup) {
            if (!TEMPORARY.matcher(statement).matches() || statement.matches("(?is)^CREATE\\s+TEMPORARY\\s+FUNCTION\\s+.+$")) {
                throw invalid("选中调试的前置语句仅支持创建临时表或视图");
            }
        }
        return new Selection(List.copyOf(setup), query);
    }

    private static void validateSetup(List<String> setup) {
        Set<String> settings = new HashSet<>();
        for (String statement : setup) {
            if (TEMPORARY.matcher(statement).matches()) continue;
            var setting = SETTING.matcher(statement);
            if (!setting.matches() || !settings.add(setting.group(1).toLowerCase(Locale.ROOT))) {
                throw invalid("准备语句仅支持临时表、视图、函数及单次设置的受支持 SET");
            }
        }
    }

    public record Selection(List<String> setup, String query) {}

    private static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : 0;
            if (quote != 0) {
                current.append(c);
                if (c == quote && next == quote) {
                    current.append(next);
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
            } else if (c == '-' && next == '-') {
                while (i + 1 < script.length() && script.charAt(i + 1) != '\n') i++;
                current.append(' ');
            } else if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) throw invalid("SQL 块注释未闭合");
                i = end + 1;
                current.append(' ');
            } else if (c == ';') {
                finish(statements, current);
            } else {
                current.append(c);
            }
        }
        if (quote != 0) throw invalid("SQL 引号未闭合");
        finish(statements, current);
        return statements;
    }

    private static void finish(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) statements.add(statement);
        current.setLength(0);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.VALIDATE_FAILED, message);
    }
}
