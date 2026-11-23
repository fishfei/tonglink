/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.execution;

import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.repository.ExecutionRepository;
import cn.tonglink.repository.FlowRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据看板接口：KPI 汇总、执行趋势、各流程采集量。
 *
 * <p>轻量模式下直接聚合 {@code execution} 表（数据量小）；
 * 数据量增长后应改为 TimescaleDB 连续聚合或物化视图，接口保持不变。</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final FlowRepository flows;
    private final ExecutionRepository executions;

    public DashboardController(FlowRepository flows, ExecutionRepository executions) {
        this.flows = flows;
        this.executions = executions;
    }

    /** KPI 汇总：流程总数、运行中数量、累计采集量、成功率、即将执行的流程。 */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        List<Flow> all = flows.flows();
        List<Execution> recent = executions.executions(null, null, 500);
        long success = recent.stream().filter(e -> "SUCCESS".equals(e.status())).count();
        long totalRecords = all.stream().mapToLong(Flow::records).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flowCount", all.size());
        result.put("activeFlows", all.stream().filter(f -> "active".equals(f.status())).count());
        result.put("executionCount", recent.size());
        result.put("successRate", recent.isEmpty() ? "—"
                : String.format("%.1f%%", success * 100.0 / recent.size()));
        result.put("totalRecords", totalRecords);
        result.put("nextRuns", all.stream()
                .filter(f -> f.nextRun() != null && !"—".equals(f.nextRun()))
                .sorted(Comparator.comparing(Flow::nextRun))
                .limit(5)
                .map(f -> Map.of("id", f.id(), "name", f.name(), "nextRun", f.nextRun()))
                .toList());
        return Result.ok(result);
    }

    /**
     * 执行趋势。
     *
     * @param range 24h / 7d / 30d，分别按小时聚合
     * @return 每个时间桶的总执行次数与成功次数
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "24h") String range) {
        int hours = switch (range) {
            case "7d" -> 168;
            case "30d" -> 720;
            default -> 24;
        };

        // 先铺满空桶，保证曲线连续（没有执行的时段也应显示 0）
        TreeMap<String, long[]> buckets = new TreeMap<>();
        LocalDateTime end = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        for (int i = hours - 1; i >= 0; i--) {
            buckets.put(end.minusHours(i).toString(), new long[2]);
        }

        for (Execution e : executions.executions(null, null, 5000)) {
            if (e.startTime() == null) {
                continue;
            }
            try {
                String key = LocalDateTime.parse(e.startTime())
                        .withMinute(0).withSecond(0).withNano(0).toString();
                long[] bucket = buckets.get(key);
                if (bucket == null) {
                    continue;
                }
                bucket[0]++;
                if ("SUCCESS".equals(e.status())) {
                    bucket[1]++;
                }
            } catch (Exception ignored) {
                // 时间格式异常的历史数据直接跳过，不影响整体趋势
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        buckets.forEach((time, values) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", time);
            point.put("total", values[0]);
            point.put("success", values[1]);
            series.add(point);
        });
        return Result.ok(series);
    }

    /** 各流程采集量与成功率，用于看板表格与柱状图。 */
    @GetMapping("/flow-stats")
    public Result<List<Map<String, Object>>> flowStats() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Flow f : flows.flows()) {
            List<Execution> list = executions.executions(f.id(), null, 100);
            long ok = list.stream().filter(e -> "SUCCESS".equals(e.status())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.id());
            row.put("name", f.name());
            row.put("projectId", f.projectId() == null ? "" : f.projectId());
            row.put("records", f.records());
            row.put("status", f.status());
            // 前端据此区分「已下线」（曾发布但被停掉）与「从未发布的草稿」
            row.put("publishedVersion", f.publishedVersion());
            row.put("nextRun", f.nextRun() == null ? "—" : f.nextRun());
            row.put("successRate", list.isEmpty() ? "—"
                    : String.format("%.1f%%", ok * 100.0 / list.size()));
            result.add(row);
        }
        return Result.ok(result);
    }
}
