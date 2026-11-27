package com.datakhaos.workflow.executor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 脚本拆分器：按分号拆分多条语句，正确处理单引号、双引号、反引号、行注释与块注释。
 *
 * <p>用于 SQL 任务的「拆分执行」模式：脚本里写多条 DDL/DML 时可顺序执行，
 * 不依赖数据库驱动的多语句开关。拆分上限 200 条，避免超大脚本误用。</p>
 */
public final class SqlScriptSplitter {

    private static final int MAX_STATEMENTS = 200;

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : 0;

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < script.length()) {
                    // 保留转义字符，避免把 \' 当成结束引号
                    current.append(script.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '#') {
                lineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                // 块注释移除后补一个空格，避免 SELECT/*x*/1 被拼成 SELECT1
                if (!current.isEmpty() && !Character.isWhitespace(current.charAt(current.length() - 1))) {
                    current.append(' ');
                }
                blockComment = true;
                i++;
                continue;
            }
            if (c == ';') {
                addStatement(statements, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addStatement(statements, current.toString());
        return statements;
    }

    private static void addStatement(List<String> statements, String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || isCommentOnly(trimmed)) {
            return;
        }
        if (statements.size() >= MAX_STATEMENTS) {
            throw new IllegalArgumentException("SQL 拆分后语句超过上限 " + MAX_STATEMENTS + " 条，请拆分任务");
        }
        statements.add(trimmed);
    }

    /** 判断片段是否只有注释（如末尾遗留的 -- 说明）。 */
    private static boolean isCommentOnly(String text) {
        String withoutBlock = text.replaceAll("(?s)/\\*.*?\\*/", "").trim();
        if (withoutBlock.isEmpty()) {
            return true;
        }
        for (String line : withoutBlock.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("#")) {
                return false;
            }
        }
        return true;
    }
}
