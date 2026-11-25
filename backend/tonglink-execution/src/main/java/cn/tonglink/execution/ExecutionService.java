/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.execution;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.connector.NodeExecutor;
import cn.tonglink.connector.NodeExecutorRegistry;
import cn.tonglink.connector.NodeLogException;
import cn.tonglink.connector.WaitSignal;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionLog;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.Variable;
import cn.tonglink.model.entity.WaitState;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.queue.ExecutionQueue;
import cn.tonglink.repository.ExecutionRepository;
import cn.tonglink.repository.FlowRepository;
import cn.tonglink.repository.MetaRepository;
import cn.tonglink.workflow.FlowService;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行引擎：把流程图变成一次可暂停、可恢复的运行。
 *
 * <p>生命周期：</p>
 * <ol>
 *   <li>{@link #submit} 落库 PENDING 记录并投递队列，立即返回执行 ID；</li>
 *   <li>队列消费者调用 {@link #execute}，按拓扑序逐节点执行并写节点记录与日志；</li>
 *   <li>延时节点抛出 {@link WaitSignal} 时，把进度快照落库、标记 WAITING 并释放线程；</li>
 *   <li>{@link #resumeDue} 由调度器周期调用，到期的执行重新入队并从断点继续；</li>
 *   <li>失败立即中断并标记 FAILED，成功则汇总采集条数、刷新流程统计。</li>
 * </ol>
 *
 * <p>数据流约定：节点输出是「行列表」。条件节点返回按端口分组的 branches，
 * 连线声明的 sourceHandle 会精确匹配对应分组，从而实现逐条数据的分支路由。</p>
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /** 毫秒精度的 ISO 时间：保证 1 毫秒的延时也不会被截断成 0，且字符串比较仍正确。 */
    private static final java.time.format.DateTimeFormatter TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRINGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<Map<String, Object>>>> ROWS_MAP_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, List<Map<String, Object>>>>> BRANCHES_TYPE =
            new TypeReference<>() {};

    private final ExecutionRepository executions;
    private final FlowRepository flowsRepo;
    private final MetaRepository meta;
    private final FlowService flowService;
    private final NodeExecutorRegistry executorRegistry;
    private final ExecutionQueue queue;
    private final ExecutionEventBus bus;

    public ExecutionService(ExecutionRepository executions, FlowRepository flowsRepo,
                            MetaRepository meta, FlowService flowService,
                            NodeExecutorRegistry executorRegistry, ExecutionQueue queue,
                            ExecutionEventBus bus) {
        this.executions = executions;
        this.flowsRepo = flowsRepo;
        this.meta = meta;
        this.flowService = flowService;
        this.executorRegistry = executorRegistry;
        this.queue = queue;
        this.bus = bus;
    }

    /** 应用启动后注册消费者，开始处理队列中的任务。 */
    @PostConstruct
    public void startConsumer() {
        queue.consume(this::execute);
    }

    /**
     * 提交一次执行。
     *
     * @param flowId         流程 ID
     * @param triggerType    manual / schedule / kafka / mqtt / webhook
     * @param idempotencyKey 幂等键，可为 null
     * @return 已落库的 PENDING 记录
     */
    public Execution submit(String flowId, String triggerType, String idempotencyKey) {
        return submit(flowId, triggerType, idempotencyKey, List.of());
    }

    /** 提交一次带初始数据的执行，供 Kafka / MQTT 等外部触发器使用。 */
    public Execution submit(String flowId, String triggerType, String idempotencyKey,
                            List<Map<String, Object>> input) {
        Flow flow = flowService.get(flowId);
        String id = IdUtil.prefix("EXEC");
        String now = now();
        Execution e = new Execution(id, flowId, flow.publishedVersion(), triggerType,
                "PENDING", now, null, 0L, null, idempotencyKey);
        executions.saveExecution(e);
        // 记录本次实际使用的图：手动运行跑草稿，定时/外部触发跑发布快照。
        // 日志页据此画图，避免新增节点后仍显示旧的发布快照。
        boolean manualRun = "manual".equals(triggerType);
        boolean usePublished = !manualRun && "active".equals(flow.status())
                && flow.publishedGraphJson() != null && !flow.publishedGraphJson().isBlank();
        executions.saveGraph(id, usePublished ? flow.publishedGraphJson() : flow.graphJson());
        if (input != null && !input.isEmpty()) {
            executions.saveInput(id, JsonUtil.stringify(input));
        }

        String flowName = flow.name() == null ? flow.id() : flow.name();
        writeLog(id, null, "INFO", "执行已入队，流程：" + flowName);
        bus.publish(id, "queued", Map.of("flowName", flowName));
        queue.publish(id);
        return e;
    }

    /**
     * 扫描并把已到期的延时断点重新投递到队列。
     *
     * @return 本次恢复的执行数
     */
    public int resumeDue() {
        List<WaitState> due = executions.dueWaits(LocalDateTime.now().format(TIME_FORMAT));
        int resumed = 0;
        // 兜底：进程在「删除断点 → 改回 RUNNING」之间崩溃时，执行会卡在 WAITING
        for (Execution stuck : executions.executions(null, "WAITING", 100)) {
            if (executions.wait(stuck.id()) == null) {
                queue.publish(stuck.id());
                resumed++;
            }
        }
        for (WaitState wait : due) {
            Execution execution = executions.execution(wait.executionId());
            if (execution == null || "SUCCESS".equals(execution.status())
                    || "FAILED".equals(execution.status())) {
                executions.deleteWait(wait.executionId());
                continue;
            }
            // 断点由 execute() 读取快照后删除；这里不能提前删，否则执行会从头重跑
            queue.publish(wait.executionId());
            resumed++;
        }
        return resumed;
    }

    /**
     * 执行指定任务（由队列消费者调用）。
     *
     * @param executionId 执行 ID
     */
    public void execute(String executionId) {
        Execution execution = executions.execution(executionId);
        if (execution == null || "SUCCESS".equals(execution.status())
                || "FAILED".equals(execution.status())) {
            return;
        }
        Flow flow = flowsRepo.flow(execution.flowId());
        if (flow == null) {
            markFailed(execution, "流程不存在：" + execution.flowId());
            return;
        }

        WaitState wait = executions.wait(executionId);
        if (wait != null && !"WAITING".equals(execution.status())) {
            // 只有处于等待态的执行才允许从断点恢复，避免重复投递导致重跑
            return;
        }
        if (wait != null && LocalDateTime.parse(wait.waitUntil()).isAfter(LocalDateTime.now())) {
            // 还没到点，队列里这条消息直接丢弃；调度器会在到期后重新投递
            return;
        }

        Map<String, List<Map<String, Object>>> outputs = new HashMap<>();
        Map<String, String> handles = new HashMap<>();
        Map<String, Map<String, List<Map<String, Object>>>> branches = new HashMap<>();
        Map<String, Object> vars;
        long records = 0L;
        int fromIndex = 0;

        if (wait != null) {
            try {
                Map<String, Object> snap = JsonUtil.parse(wait.snapshotJson(), MAP_TYPE);
                outputs = castRowsMap(snap.get("outputs"));
                handles = castStrings(snap.get("handles"));
                branches = castBranches(snap.get("branches"));
                vars = castVars(snap.get("vars"));
                records = ((Number) snap.getOrDefault("records", 0L)).longValue();
                fromIndex = ((Number) snap.getOrDefault("resumeIndex", 0)).intValue();
                // 延时节点是「等一会儿再原样放行」：断点里保存的输入就是它恢复后的输出。
                // 不写回 outputs 的话，下游 collectInput 会认为上游没有产出，拿到空列表。
                List<Map<String, Object>> passed = rows(snap.get("nodeInput"));
                outputs.put(wait.nodeId(), passed);
                executions.completeNode(executionId, wait.nodeId(),
                        System.currentTimeMillis() - LocalDateTime.parse(wait.createdAt())
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        JsonUtil.stringify(passed));
                writeLog(executionId, wait.nodeId(), "INFO",
                        "延时结束，原样放行 " + passed.size() + " 条数据给下游节点");
                bus.publish(executionId, "node-success",
                        Map.of("nodeId", wait.nodeId(), "count", passed.size()));
            } catch (Exception e) {
                markFailed(execution, "恢复延时断点失败：" + e.getMessage());
                executions.deleteWait(executionId);
                return;
            }
            executions.deleteWait(executionId);
            updateStatus(execution, "RUNNING", null, records);
            writeLog(executionId, null, "INFO", "延时到期，流程恢复执行");
            bus.publish(executionId, "resumed", Map.of("flowId", flow.id()));
        } else {
            updateStatus(execution, "RUNNING", null, 0L);
            writeLog(executionId, null, "INFO", "开始执行流程 " + flow.name());
            bus.publish(executionId, "started", Map.of("flowId", flow.id()));
            vars = loadVariables(flow.projectId());
        }

        try {
            // 优先使用入队时保存的图快照，保证日志展示与真实执行完全一致；
            // 老记录无快照时按原规则：手动运行用草稿，定时/外部触发用发布快照。
            String graphSource = executions.graph(executionId);
            if (graphSource == null || graphSource.isBlank()) {
                boolean manual = "manual".equals(execution.triggerType());
                boolean published = !manual && "active".equals(flow.status())
                        && flow.publishedGraphJson() != null && !flow.publishedGraphJson().isBlank();
                graphSource = published ? flow.publishedGraphJson() : flow.graphJson();
            }
            if (wait != null) {
                // 恢复时优先使用断点里保存的图，避免发布新版本导致断点错位
                Map<String, Object> snap = JsonUtil.parse(wait.snapshotJson(), MAP_TYPE);
                if (snap.get("graphJson") != null) {
                    graphSource = String.valueOf(snap.get("graphJson"));
                }
            }
            FlowGraph graph = JsonUtil.parse(graphSource, FlowGraph.class);
            List<GraphNode> ordered = flowService.topologicalOrder(graph);
            if (fromIndex > ordered.size()) {
                fromIndex = ordered.size();
            }

            // 预先把入边按目标节点分组，避免每个节点都遍历全部连线
            Map<String, List<GraphEdge>> incoming = new HashMap<>();
            for (GraphEdge edge : graph.connections()) {
                incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            }

            List<Map<String, Object>> triggerInput = wait == null ? executions.input(executionId) : List.of();

            for (int i = fromIndex; i < ordered.size(); i++) {
                GraphNode node = ordered.get(i);
                long start = System.currentTimeMillis();
                List<Map<String, Object>> input = collectInput(node, incoming, outputs, handles, branches);
                String title = node.title() == null ? node.type() : node.title();

                writeLog(executionId, node.id(), "INFO", "节点开始：" + title);
                bus.publish(executionId, "node-start", Map.of("nodeId", node.id(), "title", title));

                try {
                    List<Map<String, Object>> output;
                    String handle = null;
                    List<String> logs = List.of();
                    Map<String, List<Map<String, Object>>> nodeBranches = null;

                    if ("trigger".equals(node.type()) && !triggerInput.isEmpty()) {
                        // 外部触发器携带的消息就是触发器节点的输出
                        output = triggerInput;
                    } else {
                        NodeExecutor executor = executorRegistry.get(node);
                        NodeExecutor.ExecResult result = executor.execute(
                                new NodeExecutor.ExecContext(executionId, flow.id(), node, vars),
                                node.config() == null ? Map.of() : node.config(),
                                input);
                        output = result.output() == null ? List.of() : result.output();
                        handle = result.handle();
                        logs = result.logs() == null ? List.of() : result.logs();
                        nodeBranches = result.branches();
                    }

                    outputs.put(node.id(), output);
                    if (handle != null) {
                        handles.put(node.id(), handle);
                    }
                    if (nodeBranches != null) {
                        branches.put(node.id(), nodeBranches);
                    }
                    long duration = System.currentTimeMillis() - start;

                    executions.saveNode(new ExecutionNode(IdUtil.next(), executionId, node.id(),
                            title, node.type(), node.nodeVersion(), "SUCCESS", duration,
                            JsonUtil.stringify(input), JsonUtil.stringify(output), null, 0));
                    logs.forEach(line -> writeLog(executionId, node.id(), "INFO", line));
                    writeLog(executionId, node.id(), "INFO",
                            "节点完成：" + title + "，输出 " + output.size() + " 条，耗时 " + duration + "ms");
                    bus.publish(executionId, "node-success", Map.of("nodeId", node.id(), "count", output.size()));

                    if ("source".equals(node.type()) || "output".equals(node.type())) {
                        records += output.size();
                    }
                } catch (WaitSignal signal) {
                    long duration = System.currentTimeMillis() - start;
                    executions.saveNode(new ExecutionNode(IdUtil.next(), executionId, node.id(),
                            title, node.type(), node.nodeVersion(), "WAITING", duration,
                            JsonUtil.stringify(input), null, signal.getMessage(), 0));
                    Map<String, Object> snap = new LinkedHashMap<>();
                    snap.put("graphJson", graphSource);
                    snap.put("outputs", outputs);
                    snap.put("handles", handles);
                    snap.put("branches", branches);
                    snap.put("vars", vars);
                    snap.put("records", records);
                    snap.put("resumeIndex", i + 1);
                    snap.put("nodeId", node.id());
                    snap.put("nodeInput", input);
                    LocalDateTime until = LocalDateTime.now().plusNanos(signal.waitMillis() * 1_000_000L);
                    String untilText = until.format(TIME_FORMAT);
                    executions.saveWait(new WaitState(IdUtil.prefix("WAIT"), executionId,
                            untilText, i + 1, JsonUtil.stringify(snap),
                            node.id(), now()));
                    updateStatus(execution, "WAITING", null, records);
                    writeLog(executionId, node.id(), "INFO",
                            signal.getMessage() + "，已异步等待至 " + untilText);
                    bus.publish(executionId, "waiting", Map.of("nodeId", node.id(), "until", until.toString()));
                    return;
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    String error = e.getMessage() == null ? e.toString() : e.getMessage();
                    executions.saveNode(new ExecutionNode(IdUtil.next(), executionId, node.id(),
                            title, node.type(), node.nodeVersion(), "FAILED", duration,
                            JsonUtil.stringify(input), null, error, 0));
                    if (e instanceof NodeLogException nle) {
                        nle.logs().forEach(line -> writeLog(executionId, node.id(), "INFO", line));
                    }
                    writeLog(executionId, node.id(), "ERROR", "节点失败：" + title + " - " + error);
                    bus.publish(executionId, "node-failed", Map.of("nodeId", node.id(), "error", error));
                    throw e;
                }
            }

            updateStatus(execution, "SUCCESS", null, records);
            writeLog(executionId, null, "INFO", "执行成功，共采集/写入 " + records + " 条");
            bus.publish(executionId, "success", Map.of("records", records));
            updateFlowStats(flow, records);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            markFailed(execution, error);
        }
    }

    /**
     * 汇总上游输入。
     *
     * <p>三类连线会被跳过：上游根本没产出（如未执行的分支）、handle 不匹配的连线、
     * 以及条件节点按端口分流后该端口为空的连线。</p>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectInput(
            GraphNode node, Map<String, List<GraphEdge>> incoming,
            Map<String, List<Map<String, Object>>> outputs, Map<String, String> handles,
            Map<String, Map<String, List<Map<String, Object>>>> branches) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (GraphEdge edge : incoming.getOrDefault(node.id(), List.of())) {
            String declared = edge.sourceHandle();
            Map<String, List<Map<String, Object>>> byBranch = branches.get(edge.source());
            if (byBranch != null) {
                // 条件节点：只取该连线声明的端口；未声明时取所有端口
                if (declared != null && !declared.isBlank() && !"out".equals(declared)) {
                    List<Map<String, Object>> branchRows = byBranch.get(declared);
                    if (branchRows != null) {
                        input.addAll(branchRows);
                    }
                } else {
                    byBranch.values().forEach(input::addAll);
                }
                continue;
            }
            List<Map<String, Object>> upstream = outputs.get(edge.source());
            if (upstream == null) {
                continue;
            }
            String actual = handles.get(edge.source());
            boolean branchMismatch = declared != null && !declared.isBlank() && !"out".equals(declared)
                    && actual != null && !declared.equals(actual);
            if (branchMismatch) {
                continue;
            }
            input.addAll(upstream);
        }
        return input;
    }

    /** 加载变量并按项目作用域过滤：全部项目变量 + 当前项目变量，后者覆盖前者。 */
    private Map<String, Object> loadVariables(String projectId) {
        Map<String, Object> global = new LinkedHashMap<>();
        Map<String, Object> project = new LinkedHashMap<>();
        for (Variable v : meta.variables()) {
            String value = v.valueCipher() == null ? "" : v.valueCipher();
            String scope = v.scope() == null ? "" : v.scope();
            boolean isGlobal = scope.isBlank() || "all".equals(scope)
                    || "global".equals(scope) || "__all__".equals(scope);
            if (isGlobal) {
                global.put(v.key(), value);
            } else if (projectId != null && java.util.Arrays.asList(scope.split(",")).contains(projectId)) {
                project.put(v.key(), value);
            }
        }
        Map<String, Object> vars = new LinkedHashMap<>(global);
        vars.putAll(project);
        return vars;
    }

    private void markFailed(Execution execution, String error) {
        updateStatus(execution, "FAILED", error, 0L);
        writeLog(execution.id(), null, "ERROR", "执行失败：" + error);
        bus.publish(execution.id(), "failed", Map.of("error", error));
    }

    private void updateStatus(Execution e, String status, String error, long records) {
        Execution updated = new Execution(e.id(), e.flowId(), e.flowVersion(), e.triggerType(),
                status, e.startTime(), now(), records > 0 ? records : e.records(), error,
                e.idempotencyKey());
        executions.updateExecution(updated);
    }

    /** 刷新流程的累计采集量与最近运行时间。 */
    private void updateFlowStats(Flow flow, long records) {
        Flow updated = new Flow(flow.id(), flow.projectId(), flow.name(), flow.description(),
                flow.status(), flow.graphJson(), flow.publishedGraphJson(), flow.publishedVersion(),
                flow.nextRun(), now(), flow.records() + records, "100%", flow.createdAt(), now());
        flowsRepo.updateFlow(updated);
        executions.saveMetric(now(), flow.id(), null,
                JsonUtil.stringify(Map.of("projectId", flow.projectId() == null ? "" : flow.projectId())),
                JsonUtil.stringify(Map.of("records", records)));
    }

    /**
     * 写执行日志。
     *
     * <p>整体级日志的 nodeId 为空串而非 null：早期版本用 {@code Map.of} 组装带 null 的负载会抛 NPE，
     * 导致运行接口 500，这里统一做空值规范化。</p>
     */
    private void writeLog(String executionId, String nodeId, String level, String message) {
        long seq = executions.maxSeq(executionId) + 1;
        String safeNodeId = nodeId == null ? "" : nodeId;
        String safeLevel = level == null ? "INFO" : level;
        String safeMessage = message == null ? "" : message;
        ExecutionLog entry = new ExecutionLog(IdUtil.next(), executionId, safeNodeId, seq,
                safeLevel, safeMessage, now());
        executions.saveLog(entry);
        bus.publish(executionId, "log", Map.of(
                "seq", seq, "level", safeLevel, "nodeId", safeNodeId,
                "message", safeMessage, "ts", entry.ts()));
    }

    /** 是否存在运行中的执行，供调度器避免任务堆积。 */
    public boolean hasRunning(String flowId) {
        return !executions.executions(flowId, "RUNNING", 1).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castStrings(Object value) {
        if (value == null) {
            return new HashMap<>();
        }
        return JsonUtil.mapper().convertValue(value, STRINGS_TYPE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> castRowsMap(Object value) {
        if (value == null) {
            return new HashMap<>();
        }
        return JsonUtil.mapper().convertValue(value, ROWS_MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, List<Map<String, Object>>>> castBranches(Object value) {
        if (value == null) {
            return new HashMap<>();
        }
        return JsonUtil.mapper().convertValue(value, BRANCHES_TYPE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castVars(Object value) {
        return JsonUtil.mapper().convertValue(value, MAP_TYPE);
    }

    private static List<Map<String, Object>> rows(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> parsed = JsonUtil.mapper().convertValue(value, ROWS_TYPE);
        return parsed == null ? List.of() : parsed;
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }
}
