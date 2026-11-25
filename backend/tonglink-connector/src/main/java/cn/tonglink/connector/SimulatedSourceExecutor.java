/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工业协议与输出类节点的模拟执行器。
 *
 * <p>飞书等第三方节点需要凭据才能真实运行；告警节点已由 {@link AlertExecutor} 实现真实投递。
 * 轻量模式下由本执行器生成结构真实、取值合理的模拟数据，使「编排 → 运行 → 看板」
 * 整条链路可以在没有现场环境的机器上演示。OPC UA / Modbus 节点已暂时从节点面板与数据源
 * 类型目录移除，这里保留执行能力仅为兼容历史流程继续运行。</p>
 */
@Component
public class SimulatedSourceExecutor implements NodeExecutor {

    private static final Random RANDOM = new Random();

    /** 输出类节点的子类型前缀，这些节点只回执写入结果。 */
    private static final List<String> OUTPUT_PREFIXES = List.of(
            "feishu", "wecom", "redis", "influxdb", "elasticsearch");

    @Override
    public Set<String> supports() {
        return Set.of("source:opcua", "source:modbus", "source:mongodb",
                "output:wecom", "output:redis", "output:influxdb", "output:elasticsearch");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String subtype = context.node().subtype();
        boolean outputNode = OUTPUT_PREFIXES.stream().anyMatch(subtype::startsWith);

        Map<String, Object> row = new LinkedHashMap<>();
        if (outputNode) {
            row.put("status", "written");
            row.put("target", subtype);
            row.put("count", input.size());
            row.put("ts", LocalDateTime.now().withNano(0).toString());
            return new ExecResult(List.of(row), null);
        }

        // 采集类节点：模拟一条带质量戳的测点数据
        Object device = config.getOrDefault("nodeId",
                config.getOrDefault("startAddress",
                        config.getOrDefault("dbNumber",
                                config.getOrDefault("topic", "SIM-PLC-01"))));
        row.put("ts", LocalDateTime.now().withNano(0).toString());
        row.put("device", str(device));
        row.put("tag", str(config.getOrDefault("registerType", "TEMPERATURE")));
        row.put("value", Math.round((60.0 + RANDOM.nextDouble() * 30.0) * 100) / 100.0);
        row.put("quality", "GOOD");
        return new ExecResult(List.of(row), null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
