/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 逻辑节点执行器：条件分支、数据过滤、合并、延时。
 *
 * <p>表达式采用轻量语法而非脚本引擎：形如 {@code {{ $json.temp > $vars.TEMP_THRESHOLD }}}，
 * 支持 6 种比较运算符与 contains。这样做的好处是无需引入表达式引擎、可控且可解释；
 * 需要复杂逻辑时，请改用 Node.js / Python 代码节点。</p>
 */
@Component
public class LogicExecutor implements NodeExecutor {

    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<", " contains "};
    private static final long MAX_DELAY_MILLIS = 60_000L;

    @Override
    public Set<String> supports() {
        return Set.of("logic:condition", "logic:filter", "logic:merge", "logic:delay");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        return switch (context.node().subtype()) {
            case "condition" -> condition(config, input, context.vars());
            case "filter" -> filter(config, input, context.vars());
            case "merge" -> merge(config, input);
            case "delay" -> delay(config, input);
            default -> NodeExecutor.pass(input);
        };
    }

    /** 条件分支：命中返回 true 分支，否则返回 false 分支（下游按 handle 过滤）。 */
    private ExecResult condition(Map<String, Object> config, List<Map<String, Object>> input,
                                 Map<String, Object> vars) {
        boolean pass = !input.isEmpty()
                && evaluate(String.valueOf(config.getOrDefault("expression", "true")), input.get(0), vars);
        return new ExecResult(pass ? input : List.of(), pass ? "true" : "false");
    }

    /** 数据过滤：逐行判断，只保留命中的行。 */
    private ExecResult filter(Map<String, Object> config, List<Map<String, Object>> input,
                              Map<String, Object> vars) {
        String expression = String.valueOf(config.getOrDefault("expression", "true"));
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : input) {
            if (evaluate(expression, row, vars)) {
                kept.add(row);
            }
        }
        return new ExecResult(kept, null);
    }

    /** 数据合并：可选去重与最大条数保护。 */
    private ExecResult merge(Map<String, Object> config, List<Map<String, Object>> input) {
        List<Map<String, Object>> merged = new ArrayList<>(input);
        if ("是".equals(String.valueOf(config.getOrDefault("dedupe", "否")))) {
            merged = new ArrayList<>(new LinkedHashSet<>(merged));
        }
        int max = intOf(config.get("maxItems"), 5000);
        if (merged.size() > max) {
            merged = new ArrayList<>(merged.subList(0, max));
        }
        return new ExecResult(merged, null);
    }

    /** 延时：上限 60 秒，避免流程长时间占用执行线程。 */
    private ExecResult delay(Map<String, Object> config, List<Map<String, Object>> input) {
        long duration = Math.max(0, intOf(config.get("duration"), 1));
        String unit = String.valueOf(config.getOrDefault("unit", "秒"));
        long millis = unit.contains("毫秒") ? duration
                : unit.contains("分钟") ? duration * 60_000L
                : duration * 1_000L;
        try {
            Thread.sleep(Math.min(millis, MAX_DELAY_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ExecResult(input, null);
    }

    /**
     * 计算表达式。
     *
     * <p>公开为静态方法便于单元测试直接验证比较语义。</p>
     *
     * @param expression 形如 {@code {{ $json.temp > 85 }}} 或 {@code true}
     * @param json       当前行数据
     * @param vars       变量表
     */
    public static boolean evaluate(String expression, Map<String, Object> json, Map<String, Object> vars) {
        String expr = expression.trim().replace("{{", "").replace("}}", "").trim();
        if (expr.isBlank() || "true".equals(expr)) {
            return true;
        }
        if ("false".equals(expr)) {
            return false;
        }
        for (String op : OPERATORS) {
            int idx = expr.indexOf(op);
            if (idx <= 0) {
                continue;
            }
            String left = resolve(expr.substring(0, idx).trim(), json, vars);
            String right = resolve(expr.substring(idx + op.length()).trim(), json, vars);
            return compare(left, right, op.trim());
        }
        return Boolean.parseBoolean(resolve(expr, json, vars));
    }

    /** 把 {@code $json.x} / {@code $vars.y} / 字面量解析成字符串。 */
    private static String resolve(String token, Map<String, Object> json, Map<String, Object> vars) {
        String t = token.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        if (t.startsWith("$json.")) {
            return String.valueOf(json.getOrDefault(t.substring(6), ""));
        }
        if (t.startsWith("$vars.")) {
            return String.valueOf(vars.getOrDefault(t.substring(6), ""));
        }
        return t;
    }

    /** 先按数值比较，失败则退回字典序比较。 */
    private static boolean compare(String left, String right, String op) {
        try {
            double l = Double.parseDouble(left);
            double r = Double.parseDouble(right);
            return switch (op) {
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                case "==" -> Double.compare(l, r) == 0;
                case "!=" -> Double.compare(l, r) != 0;
                default -> left.contains(right);
            };
        } catch (NumberFormatException e) {
            return switch (op) {
                case ">" -> left.compareTo(right) > 0;
                case "<" -> left.compareTo(right) < 0;
                case ">=" -> left.compareTo(right) >= 0;
                case "<=" -> left.compareTo(right) <= 0;
                case "==" -> left.equals(right);
                case "!=" -> !left.equals(right);
                default -> left.contains(right);
            };
        }
    }

    private static int intOf(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
