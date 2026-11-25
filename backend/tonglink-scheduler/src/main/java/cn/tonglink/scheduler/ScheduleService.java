/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.scheduler;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.execution.ExecutionService;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.repository.FlowRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 轻量调度器：每 5 秒扫描一次已发布流程，到点就投递执行。
 *
 * <p>为什么不用 Quartz：单实例轻量部署下，一个固定频率的 tick + 数据库里的
 * {@code next_run} 已经足够，且重启后无需恢复 JobStore；多实例场景应改用
 * Quartz 集群或 Redis 分布式锁，避免同一时刻重复触发。</p>
 *
 * <p>三种触发模式（秒 / 分钟 / Cron）在 {@link #computeNext} 中统一换算。</p>
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** 表示「无计划」的占位值，与前端展示保持一致。 */
    private static final String NONE = "—";

    private final FlowRepository flows;
    private final ExecutionService executionService;

    public ScheduleService(FlowRepository flows, ExecutionService executionService) {
        this.flows = flows;
        this.executionService = executionService;
    }

    /**
     * 定时扫描。
     *
     * <p>只处理 {@code status=active} 且包含定时触发节点的流程；
     * 若上一次执行仍在运行，则跳过本次（SKIP 策略），避免任务堆积。</p>
     */
    @Scheduled(fixedDelayString = "${tonglink.scheduler.interval-ms:5000}")
    public void tick() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 先恢复到期的延时节点，再处理定时触发；两者共用同一个执行队列
        int resumed = executionService.resumeDue();
        if (resumed > 0) {
            log.info("本轮恢复 {} 个延时等待中的执行", resumed);
        }
        for (Flow flow : flows.flows()) {
            if (!"active".equals(flow.status())) {
                continue;
            }
            GraphNode trigger = triggerOf(flow);
            if (trigger == null) {
                continue;
            }
            Map<String, Object> config = trigger.config() == null ? Map.of() : trigger.config();
            String next = flow.nextRun();
            if (next == null || next.isBlank() || NONE.equals(next)) {
                updateNext(flow, computeNext(config, now));
                continue;
            }
            try {
                if (LocalDateTime.parse(next).isAfter(now)) {
                    continue;
                }
                if (executionService.hasRunning(flow.id())) {
                    // 上一个周期还没跑完，先顺延，等下个周期再看
                    updateNext(flow, computeNext(config, now));
                    continue;
                }
                executionService.submit(flow.id(), "schedule", null);
                updateNext(flow, computeNext(config, now));
            } catch (Exception e) {
                log.warn("流程 {} 调度失败：{}", flow.id(), e.getMessage());
                updateNext(flow, computeNext(config, now));
            }
        }
    }

    /** 取流程中的第一个定时触发节点。 */
    private GraphNode triggerOf(Flow flow) {
        try {
            String source = flow.publishedGraphJson() == null || flow.publishedGraphJson().isBlank()
                    ? flow.graphJson() : flow.publishedGraphJson();
            FlowGraph graph = JsonUtil.parse(source, FlowGraph.class);
            if (graph == null) {
                return null;
            }
            List<GraphNode> triggers = graph.nodes().stream()
                    .filter(n -> "trigger".equals(n.type()) && "schedule".equals(n.subtype()))
                    .toList();
            return triggers.isEmpty() ? null : triggers.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算下一次执行时间。
     *
     * @param config 触发节点配置
     * @param from   基准时间
     * @return ISO-8601 字符串；配置非法时返回 {@code —}
     */
    public String computeNext(Map<String, Object> config, LocalDateTime from) {
        String mode = String.valueOf(config.getOrDefault("mode", "minutes"));
        try {
            if ("seconds".equals(mode)) {
                long seconds = Math.max(1, parseLong(config.get("everySeconds"), 30));
                long epoch = from.atZone(ZoneId.systemDefault()).toEpochSecond();
                long next = (epoch / seconds + 1) * seconds;
                return toLocal(next);
            }
            if ("cron".equals(mode)) {
                String expression = String.valueOf(config.getOrDefault("cron", "0 0 8 * * ?"));
                LocalDateTime next = CronExpression.parse(expression).next(from);
                return next == null ? NONE : next.withNano(0).toString();
            }
            long minutes = Math.max(1, parseLong(config.get("everyMinutes"), 5));
            // 按分钟对齐：每 5 分钟即从整点起 00、05、10…，符合工业采集习惯
            long epochMinutes = from.withSecond(0).atZone(ZoneId.systemDefault()).toEpochSecond() / 60;
            long next = (epochMinutes / minutes + 1) * minutes * 60;
            return toLocal(next);
        } catch (Exception e) {
            log.debug("调度表达式解析失败：{}", e.getMessage());
            return NONE;
        }
    }

    private String toLocal(long epochSecond) {
        return LocalDateTime.ofEpochSecond(epochSecond, 0,
                ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochSecond(epochSecond)))
                .withNano(0).toString();
    }

    private void updateNext(Flow flow, String next) {
        Flow updated = new Flow(flow.id(), flow.projectId(), flow.name(), flow.description(),
                flow.status(), flow.graphJson(), flow.publishedGraphJson(), flow.publishedVersion(),
                next, flow.lastRun(), flow.records(), flow.successRate(), flow.createdAt(),
                LocalDateTime.now().withNano(0).toString());
        flows.updateFlow(updated);
    }

    private static long parseLong(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
