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
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 定时调度器：每条流程一个一次性精确 timer，由低频对齐器负责注册与重建。
 *
 * <p>触发时刻不依赖 5 秒轮询：对齐器只负责发现新增/变更的流程并注册 timer，
 * timer 到点后通过数据库条件认领（{@code next_run} 仍是期望值才推进）保证多实例下
 * 只有一个实例真正提交执行，无需 leader 选举或分布式锁。</p>
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
    private final ScheduledThreadPoolExecutor timers;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> signatures = new ConcurrentHashMap<>();
    private final boolean autoAlign;
    private final long misfireGraceMs;

    public ScheduleService(FlowRepository flows, ExecutionService executionService,
                           @Value("${tonglink.scheduler.timer-threads:3}") int timerThreads,
                           @Value("${tonglink.scheduler.interval-ms:5000}") long intervalMs,
                           @Value("${tonglink.scheduler.misfire-grace-ms:60000}") long misfireGraceMs,
                           @Value("${tonglink.scheduler.enabled:true}") boolean autoAlign) {
        this.flows = flows;
        this.executionService = executionService;
        this.autoAlign = autoAlign;
        this.misfireGraceMs = Math.max(intervalMs, Math.max(1000L, misfireGraceMs));

        int threads = Math.max(1, Math.min(32, timerThreads));
        AtomicInteger seq = new AtomicInteger();
        this.timers = new ScheduledThreadPoolExecutor(threads, r -> {
            Thread t = new Thread(r, "tl-scheduler-timer-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // 流程改配置会 cancel 旧 timer：立即从无界延迟队列中移除，避免长期堆积。
        this.timers.setRemoveOnCancelPolicy(true);
        // 停机时不再执行已排队但未到点的触发。
        this.timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * 低频对齐：恢复延时断点，并让内存中的 timer 与数据库里的流程状态保持一致。
     *
     * <p>真正到点触发由 timer 完成，本方法不做「到点就执行」，因此 5 秒间隔不是调度精度。</p>
     */
    @Scheduled(fixedDelayString = "${tonglink.scheduler.interval-ms:5000}")
    public void tick() {
        try {
            // 先清掉卡死的 RUNNING，避免它们永久挡住对应流程的定时触发
            int cleaned = executionService.failStaleRunning();
            if (cleaned > 0) {
                log.warn("本轮判定 {} 条执行卡死并标记失败", cleaned);
            }
            int resumed = executionService.resumeDue();
            if (resumed > 0) {
                log.info("本轮恢复 {} 个延时等待中的执行", resumed);
            }
            if (autoAlign) {
                alignTimers();
            }
        } catch (Exception e) {
            // 数据库短暂不可用时跳过本轮即可；下一tick会再扫一次。
            log.warn("本轮定时调度跳过：数据库暂不可用（{}）", e.getMessage());
        }
    }

    /**
     * 对齐已发布流程与运行中的 timer：新增、变更、下线的流程都会在这里被重建或取消。
     *
     * <p>公开供集成测试在禁用自动对齐时手动驱动。</p>
     */
    public void alignTimers() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Set<String> desired = new HashSet<>();
        for (Flow flow : flows.flows()) {
            if (!"active".equals(flow.status())) {
                continue;
            }
            GraphNode trigger = triggerOf(flow);
            if (trigger == null) {
                continue;
            }
            Map<String, Object> config = trigger.config() == null ? Map.of() : trigger.config();
            if ("否".equals(String.valueOf(config.getOrDefault("enabled", "是")))) {
                continue;
            }
            String next = flow.nextRun();
            if (parse(next) == null) {
                String computed = computeNext(config, now);
                if (NONE.equals(computed)) {
                    continue;
                }
                // 初次调度也走条件认领，避免多实例同时启动时各自初始化出不同时间。
                flows.claimNextRun(flow.id(), next, computed);
                Flow fresh = flows.flow(flow.id());
                next = fresh == null ? computed : fresh.nextRun();
            }
            LocalDateTime at = parse(next);
            if (at == null) {
                continue;
            }
            desired.add(flow.id());
            String signature = flow.status() + "|" + flow.publishedVersion() + "|" + next;
            if (signature.equals(signatures.get(flow.id())) && futures.containsKey(flow.id())) {
                continue;
            }
            register(flow.id(), next, at, signature);
        }
        for (String id : new HashSet<>(futures.keySet())) {
            if (!desired.contains(id)) {
                cancel(id);
            }
        }
    }

    private void register(String flowId, String next, LocalDateTime at, String signature) {
        cancel(flowId);
        long delay = Math.max(0L, Duration.between(LocalDateTime.now().withNano(0), at).toMillis());
        ScheduledFuture<?> future = timers.schedule(() -> fire(flowId, next), delay,
                TimeUnit.MILLISECONDS);
        futures.put(flowId, future);
        signatures.put(flowId, signature);
    }

    /**
     * timer 到点回调：先条件认领，认领成功才提交执行。
     *
     * <p>公开是为了让集成测试可以在不等待真实时间的情况下验证多实例认领语义。</p>
     *
     * @param flowId       流程 ID
     * @param expectedNext 注册 timer 时读到的 next_run 值
     */
    public void fire(String flowId, String expectedNext) {
        try {
            Flow current = flows.flow(flowId);
            LocalDateTime scheduled = parse(expectedNext);
            if (current == null || !"active".equals(current.status()) || scheduled == null) {
                cancel(flowId);
                return;
            }
            if (!expectedNext.equals(current.nextRun())) {
                // 已被其他实例认领，或流程配置已变更，本次 timer 作废。
                return;
            }
            GraphNode trigger = triggerOf(current);
            if (trigger == null) {
                cancel(flowId);
                return;
            }
            Map<String, Object> config = trigger.config() == null ? Map.of() : trigger.config();
            if ("否".equals(String.valueOf(config.getOrDefault("enabled", "是")))) {
                cancel(flowId);
                return;
            }

            LocalDateTime now = LocalDateTime.now().withNano(0);
            String next = computeNext(config, now);
            if (NONE.equals(next)) {
                log.warn("流程 {} 的调度表达式非法，停止自动触发", flowId);
                cancel(flowId);
                return;
            }
            if (!flows.claimNextRun(flowId, expectedNext, next)) {
                // 条件认领失败：另一个实例已经推进了时钟，本实例不触发。
                return;
            }

            long latenessMs = Duration.between(scheduled, now).toMillis();
            boolean catchUp = "是".equals(String.valueOf(config.getOrDefault("catchUp", "否")));
            if (latenessMs > misfireGraceMs && !catchUp) {
                log.warn("流程 {} 错过执行窗口（迟到 {} ms），按跳过策略处理", flowId, latenessMs);
            } else if (executionService.hasRunning(flowId)) {
                // 上一个周期还没跑完，顺延到下一个周期，避免任务堆积。
                log.info("流程 {} 上一轮仍在执行，跳过本次定时触发", flowId);
            } else {
                String idempotencyKey = "schedule:" + flowId + ":" + expectedNext;
                executionService.submit(flowId, "schedule", idempotencyKey);
            }
            scheduleNext(flowId, current, next);
        } catch (Exception e) {
            log.warn("流程 {} 定时触发失败：{}", flowId, e.getMessage());
        }
    }

    /** 用认领后写回的新时间注册下一次 timer。 */
    private void scheduleNext(String flowId, Flow current, String next) {
        LocalDateTime at = parse(next);
        if (at == null) {
            cancel(flowId);
            return;
        }
        String signature = current.status() + "|" + current.publishedVersion() + "|" + next;
        register(flowId, next, at, signature);
    }

    private void cancel(String flowId) {
        ScheduledFuture<?> future = futures.remove(flowId);
        if (future != null) {
            future.cancel(false);
        }
        signatures.remove(flowId);
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

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank() || NONE.equals(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).withNano(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String toLocal(long epochSecond) {
        return LocalDateTime.ofEpochSecond(epochSecond, 0,
                ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochSecond(epochSecond)))
                .withNano(0).toString();
    }

    private static long parseLong(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PreDestroy
    public void close() {
        futures.keySet().forEach(this::cancel);
        timers.shutdownNow();
    }
}
