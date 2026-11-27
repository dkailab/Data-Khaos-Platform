package com.datakhaos.workflow.executor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节点前置条件求值器。
 *
 * <p>支持两种写法，都会先做 {@code ${param}} 参数替换：</p>
 * <ul>
 *   <li>纯表达式：{@code ${bizDate} >= '2026-01-01' && ${env} == 'prod'}</li>
 *   <li>脚本片段：{@code if [ ${bizDate} >= '2026-01-01' ] then exit 0; else exit 1;}</li>
 * </ul>
 *
 * <p>运算符：{@code == = != >= <= > <}，逻辑：{@code && ||}（不支持括号）。
 * 数值两侧按数值比较，其余按字符串比较；{@code > < >= <=} 要求两侧可解析为数值。
 * 空表达式视为「始终执行」，表达式非法时抛 {@link IllegalArgumentException}，由调度器判为节点失败，
 * 避免把配置错误静默放过。</p>
 */
public final class PreconditionEvaluator {

    private static final Pattern IF_BLOCK = Pattern.compile("(?is)^\\s*if\\s*\\[(.*?)]\\s*then\\b.*$");
    private static final Pattern PARAM = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern COMPARISON =
            Pattern.compile("^(.*?)\\s*(>=|<=|==|!=|>|<|=)\\s*(.*?)$", Pattern.DOTALL);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private PreconditionEvaluator() {
    }

    /** 求值；表达式为空返回 true。 */
    public static boolean evaluate(String expression, Map<String, Object> params) {
        String normalized = normalize(expression);
        if (normalized.isEmpty()) {
            return true;
        }
        for (String orPart : split(normalized, "||")) {
            boolean all = true;
            for (String andPart : split(orPart, "&&")) {
                if (!atom(andPart, params)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String expression) {
        if (expression == null) {
            return "";
        }
        String text = expression.trim();
        if (text.isEmpty()) {
            return "";
        }
        Matcher matcher = IF_BLOCK.matcher(text);
        if (matcher.matches()) {
            text = matcher.group(1).trim();
        }
        return text;
    }

    private static boolean atom(String raw, Map<String, Object> params) {
        String atom = raw.trim();
        if (atom.isEmpty()) {
            throw new IllegalArgumentException("前置条件存在空表达式");
        }
        if ("true".equalsIgnoreCase(atom)) {
            return true;
        }
        if ("false".equalsIgnoreCase(atom)) {
            return false;
        }
        Matcher matcher = COMPARISON.matcher(atom);
        if (!matcher.matches()) {
            // 没有比较运算符：按真值判断（非空、非 false、非 0）
            return truthy(resolve(atom, params));
        }
        String left = resolve(matcher.group(1).trim(), params);
        String operator = matcher.group(2);
        String right = resolve(matcher.group(3).trim(), params);
        return compare(left, operator, right, atom);
    }

    private static boolean compare(String left, String operator, String right, String expression) {
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return apply(leftNumber.compareTo(rightNumber), operator, expression);
        }
        // 日期/时间按字典序比较：ISO 格式本身可按字符串排序，业务日期条件最常见
        if (isIsoLike(left) && isIsoLike(right)) {
            return apply(left.compareTo(right), operator, expression);
        }
        if ("==".equals(operator) || "=".equals(operator)) {
            return left.equals(right);
        }
        if ("!=".equals(operator)) {
            return !left.equals(right);
        }
        throw new IllegalArgumentException("前置条件的 " + operator + " 要求两侧为数值或 ISO 日期: " + expression);
    }

    private static boolean apply(int cmp, String operator, String expression) {
        return switch (operator) {
            case "==", "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> throw new IllegalArgumentException("不支持的前置条件运算符: " + operator + "（" + expression + "）");
        };
    }

    private static boolean isIsoLike(String text) {
        return text.matches("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?");
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String text = stripQuotes(value.trim());
        if (text.isEmpty() || "false".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text)) {
            return false;
        }
        BigDecimal numeric = number(text);
        return numeric == null || numeric.compareTo(ZERO) != 0;
    }

    private static BigDecimal number(String value) {
        if (value == null) {
            return null;
        }
        String text = stripQuotes(value.trim());
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String resolve(String text, Map<String, Object> params) {
        if (text == null) {
            return "";
        }
        Matcher matcher = PARAM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = params == null ? null : params.get(matcher.group(1).trim());
            String replacement = value == null ? "" : String.valueOf(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return stripQuotes(out.toString().trim());
    }

    private static String stripQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /** 按逻辑运算符拆分，忽略引号内的同名文本。 */
    private static List<String> split(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == operator.charAt(0) && i + 1 < expression.length()
                    && expression.charAt(i + 1) == operator.charAt(1)) {
                parts.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }
}
