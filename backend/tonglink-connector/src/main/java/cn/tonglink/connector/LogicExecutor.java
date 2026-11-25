/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 逻辑节点执行器：条件分支、数据过滤、合并、延时等待。
 *
 * <p>表达式采用轻量语法而非脚本引擎：形如 {@code {{ $json.temp > $vars.TEMP_THRESHOLD }}}。
 * 支持 {@code == / ===}、{@code != / !==}、{@code > >= < <=}、{@code contains}，
 * 以及用 {@code &&} / {@code ||} 组合多个条件。复杂逻辑请改用代码节点。</p>
 *
 * <p>延时节点不再 {@code Thread.sleep}：它抛出 {@link WaitSignal}，由执行引擎把断点
 * 持久化后释放工作线程，到期再恢复。这样 5 分钟的等待不会占住队列消费者 5 分钟。</p>
 */
@Component
public class LogicExecutor implements NodeExecutor {

    /** 单次延时上限 24 小时；超过直接报错，绝不静默截断。 */
    public static final long MAX_DELAY_MILLIS = 86_400_000L;

    private static final String[] OPERATORS = {">=", "<=", "===", "!==", "==", "!=", ">", "<", " contains "};

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

    /**
     * 条件分支：逐条数据求值，true / false 分别进入自己的输出端口。
     *
     * <p>早期版本只看第一行并把整个输入列表推向一个分支，多行数据会被错误地全部归类。
     * 现在返回 {@code branches}，执行引擎按连线的 sourceHandle 精确分流。</p>
     */
    private ExecResult condition(Map<String, Object> config, List<Map<String, Object>> input,
                                 Map<String, Object> vars) {
        String expression = String.valueOf(config.getOrDefault("expression", "true"));
        String unmatched = String.valueOf(config.getOrDefault("unmatched", "走默认分支"));
        boolean keepUnmatched = !"丢弃".equals(unmatched);
        List<Map<String, Object>> yes = new ArrayList<>();
        List<Map<String, Object>> no = new ArrayList<>();
        for (Map<String, Object> row : input) {
            if (evaluate(expression, row, vars)) {
                yes.add(row);
            } else if (keepUnmatched) {
                no.add(row);
            }
        }
        Map<String, List<Map<String, Object>>> branches = new LinkedHashMap<>();
        branches.put("true", yes);
        branches.put("false", no);
        return ExecResult.branched(branches);
    }

    /** 数据过滤：逐行判断，只保留命中的行，并遵守最大保留条数。 */
    private ExecResult filter(Map<String, Object> config, List<Map<String, Object>> input,
                              Map<String, Object> vars) {
        String expression = String.valueOf(config.getOrDefault("expression", "true"));
        int max = intOf(config.get("maxItems"), 1000);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : input) {
            if (kept.size() >= max) {
                break;
            }
            if (evaluate(expression, row, vars)) {
                kept.add(row);
            }
        }
        return new ExecResult(kept, null);
    }

    /** 数据合并：支持追加、按字段合并、覆盖三种策略。 */
    private ExecResult merge(Map<String, Object> config, List<Map<String, Object>> input) {
        String strategy = String.valueOf(config.getOrDefault("strategy", "追加"));
        List<Map<String, Object>> merged;
        if ("按字段合并".equals(strategy)) {
            String field = String.valueOf(config.getOrDefault("matchField", ""));
            if (field.isBlank()) {
                throw new IllegalArgumentException("按字段合并必须填写匹配字段");
            }
            Map<Object, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> row : input) {
                Object key = row.get(field);
                Map<String, Object> target = grouped.computeIfAbsent(key, k -> new LinkedHashMap<>());
                target.putAll(row);
            }
            merged = new ArrayList<>(grouped.values());
        } else if ("覆盖".equals(strategy)) {
            // 同名字段后出现的覆盖先出现的，最终输出一行聚合结果
            Map<String, Object> one = new LinkedHashMap<>();
            input.forEach(one::putAll);
            merged = one.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(one));
        } else {
            merged = new ArrayList<>(input);
        }
        if ("是".equals(String.valueOf(config.getOrDefault("dedupe", "否")))) {
            merged = new ArrayList<>(new LinkedHashSet<>(merged));
        }
        int max = intOf(config.get("maxItems"), 5000);
        if (merged.size() > max) {
            merged = new ArrayList<>(merged.subList(0, max));
        }
        return new ExecResult(merged, null);
    }

    /** 延时：发出异步等待信号，由引擎持久化断点并释放线程。 */
    private ExecResult delay(Map<String, Object> config, List<Map<String, Object>> input) {
        long duration = longOf(config.get("duration"), 1);
        String unit = String.valueOf(config.getOrDefault("unit", "秒"));
        long millis = unit.contains("毫秒") ? duration
                : unit.contains("分钟") ? duration * 60_000L
                : unit.contains("小时") ? duration * 3_600_000L
                : duration * 1_000L;
        if (millis < 0) {
            throw new IllegalArgumentException("延时时长不能为负数");
        }
        if (millis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("单次延时不得超过 24 小时，请拆分为多个延时节点");
        }
        throw new WaitSignal(millis, "延时等待 " + duration + " " + unit);
    }

    /**
     * 计算布尔表达式。
     *
     * @param expression 形如 {@code {{ $json.temp > 85 && $vars.ENABLED == true }}}
     * @param json       当前行数据
     * @param vars       变量表
     */
    public static boolean evaluate(String expression, Map<String, Object> json, Map<String, Object> vars) {
        String expr = unwrap(expression);
        if (expr.isEmpty() || "true".equalsIgnoreCase(expr)) {
            return true;
        }
        if ("false".equalsIgnoreCase(expr)) {
            return false;
        }
        // 先处理逻辑组合，& / | 的优先级低于比较运算
        List<String> orParts = splitTopLevel(expr, "||");
        if (orParts.size() > 1) {
            return orParts.stream().anyMatch(p -> evaluate(p, json, vars));
        }
        List<String> andParts = splitTopLevel(expr, "&&");
        if (andParts.size() > 1) {
            return andParts.stream().allMatch(p -> evaluate(p, json, vars));
        }
        // 去掉布尔包裹后，真正的比较运算最多只应有一个
        for (String op : OPERATORS) {
            int idx = expr.indexOf(op);
            if (idx <= 0) {
                continue;
            }
            String left = resolve(expr.substring(0, idx), json, vars);
            String right = resolve(expr.substring(idx + op.length()), json, vars);
            return compare(left, right, canonical(op));
        }
        return Boolean.parseBoolean(resolve(expr, json, vars));
    }

    private static String unwrap(String expression) {
        String expr = expression == null ? "" : expression.trim();
        while (expr.startsWith("{{") && expr.endsWith("}}")) {
            expr = expr.substring(2, expr.length() - 2).trim();
        }
        if (expr.startsWith("(") && expr.endsWith(")")) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }
        return expr;
    }

    /** 只在括号外切分，避免把字符串里的 & 当成逻辑运算。 */
    private static List<String> splitTopLevel(String expr, String token) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (depth == 0 && i + token.length() <= expr.length()
                    && expr.startsWith(token, i)) {
                parts.add(expr.substring(from, i).trim());
                i += token.length() - 1;
                from = i + 1;
            }
        }
        parts.add(expr.substring(from).trim());
        return parts;
    }

    private static String canonical(String op) {
        return switch (op) {
            case "===" -> "==";
            case "!==" -> "!=";
            default -> op.trim();
        };
    }

    /** 把 {@code $json.x} / {@code $vars.y} / 字面量解析成字符串。 */
    private static String resolve(String token, Map<String, Object> json, Map<String, Object> vars) {
        String t = token.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        if (t.startsWith("$json.")) {
            return path(json, t.substring(6));
        }
        if (t.startsWith("$vars.")) {
            return path(vars, t.substring(6));
        }
        return t;
    }

    /** 支持一层以上的点号路径，例如 {@code $json.device.id}。 */
    private static String path(Map<String, Object> source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(part);
        }
        return current == null ? "" : String.valueOf(current);
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
            boolean same = left.equalsIgnoreCase(right);
            return switch (op) {
                case ">" -> left.compareTo(right) > 0;
                case "<" -> left.compareTo(right) < 0;
                case ">=" -> left.compareTo(right) >= 0;
                case "<=" -> left.compareTo(right) <= 0;
                case "==" -> same;
                case "!=" -> !same;
                default -> left.contains(right);
            };
        }
    }

    private static int intOf(Object value, int fallback) {
        return (int) Math.min(longOf(value, fallback), Integer.MAX_VALUE);
    }

    private static long longOf(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
