/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.tonglink.model.flow.GraphNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 逻辑节点回归测试：条件分支、过滤、合并、延时。 */
class LogicExecutorTest {

    private final LogicExecutor executor = new LogicExecutor();

    private static NodeExecutor.ExecResult run(String subtype, Map<String, Object> config,
                                               List<Map<String, Object>> input) {
        GraphNode node = new GraphNode("n1", "logic", subtype, "1", subtype, Map.of(), config);
        return new LogicExecutor().execute(new NodeExecutor.ExecContext("E1", "F1", node, Map.of()),
                config, input);
    }

    @Test
    void conditionSplitsEveryRowByItsOwnResult() {
        Map<String, Object> config = Map.of("expression", "{{ $json.temp > $vars.LIMIT }}");
        List<Map<String, Object>> input = List.of(
                Map.of("temp", 90), Map.of("temp", 10), Map.of("temp", 95));
        GraphNode node = new GraphNode("n1", "logic", "condition", "1", "条件", Map.of(), config);
        NodeExecutor.ExecResult result = executor.execute(
                new NodeExecutor.ExecContext("E1", "F1", node, Map.of("LIMIT", 85)), config, input);

        assertEquals(2, result.branches().get("true").size());
        assertEquals(1, result.branches().get("false").size());
        assertEquals(10, result.branches().get("false").get(0).get("temp"));
    }

    @Test
    void conditionCanDropUnmatchedRows() {
        Map<String, Object> config = Map.of("expression", "{{ $json.temp > 85 }}", "unmatched", "丢弃");
        List<Map<String, Object>> input = List.of(Map.of("temp", 90), Map.of("temp", 10));
        NodeExecutor.ExecResult result = run("condition", config, input);
        assertEquals(1, result.branches().get("true").size());
        assertTrue(result.branches().get("false").isEmpty());
    }

    @Test
    void filterSupportsStrictEqualityAndLogicalOperators() {
        Map<String, Object> config = Map.of(
                "expression", "{{ $json.quality === \"GOOD\" && $json.temp >= 20 }}");
        List<Map<String, Object>> input = List.of(
                Map.of("quality", "GOOD", "temp", 25),
                Map.of("quality", "BAD", "temp", 25),
                Map.of("quality", "GOOD", "temp", 5));
        NodeExecutor.ExecResult result = run("filter", config, input);
        assertEquals(1, result.output().size());
        assertEquals(25, result.output().get(0).get("temp"));
    }

    @Test
    void filterRespectsMaxItems() {
        Map<String, Object> config = Map.of("expression", "true", "maxItems", 2);
        NodeExecutor.ExecResult result = run("filter", config,
                List.of(Map.of("i", 1), Map.of("i", 2), Map.of("i", 3)));
        assertEquals(2, result.output().size());
    }

    @Test
    void mergeStrategiesWorkAsDocumented() {
        List<Map<String, Object>> input = List.of(
                Map.of("device_id", "A", "temp", 10),
                Map.of("device_id", "B", "temp", 20),
                Map.of("device_id", "A", "humidity", 60));

        assertEquals(3, run("merge", Map.of("strategy", "追加"), input).output().size());

        List<Map<String, Object>> byField = run("merge",
                Map.of("strategy", "按字段合并", "matchField", "device_id"), input).output();
        assertEquals(2, byField.size());
        assertEquals(60, byField.stream().filter(r -> "A".equals(r.get("device_id")))
                .findFirst().orElseThrow().get("humidity"));

        List<Map<String, Object>> overwritten = run("merge", Map.of("strategy", "覆盖"), input).output();
        assertEquals(1, overwritten.size());
        assertEquals(60, overwritten.get(0).get("humidity"));
    }

    @Test
    void mergeWithoutMatchFieldFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> run("merge", Map.of("strategy", "按字段合并"),
                        List.of(Map.of("device_id", "A"))));
    }

    @Test
    void delaySignalsInsteadOfBlockingAndNeverTruncatesSilently() {
        Map<String, Object> config = Map.of("duration", 5, "unit", "分钟");
        WaitSignal signal = assertThrows(WaitSignal.class,
                () -> run("delay", config, List.of(Map.of("i", 1))));
        assertEquals(300_000L, signal.waitMillis());

        Map<String, Object> tooLong = Map.of("duration", 25, "unit", "小时");
        assertThrows(IllegalArgumentException.class,
                () -> run("delay", tooLong, List.of()));
    }

    @Test
    void expressionParserHandlesContainsAndVariables() {
        assertTrue(LogicExecutor.evaluate("{{ $json.name contains 'PLC' }}",
                Map.of("name", "PLC-01"), Map.of()));
        assertFalse(LogicExecutor.evaluate("{{ $json.temp != $vars.LIMIT || false }}",
                Map.of("temp", 80), Map.of("LIMIT", 80)));
        assertTrue(LogicExecutor.evaluate("{{ $vars.ENABLED === true }}",
                Map.of(), Map.of("ENABLED", true)));
    }
}
