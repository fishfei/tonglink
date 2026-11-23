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
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionLog;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.queue.ExecutionQueue;
import cn.tonglink.repository.ExecutionRepository;
import cn.tonglink.repository.FlowRepository;
import cn.tonglink.repository.MetaRepository;
import cn.tonglink.security.CryptoService;
import cn.tonglink.workflow.FlowService;
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
 * 执行引擎：把流程图变成按拓扑序的一次运行。
 *
 * <p>一次执行的生命周期：</p>
 * <ol>
 *   <li>{@link #submit} 落库 PENDING 记录并投递到队列，立即返回执行 ID（前端不阻塞）；</li>
 *   <li>队列消费者调用 {@link #execute}，按拓扑序逐节点执行，每步写入节点记录与日志；</li>
 *   <li>失败立即中断并标记 FAILED，成功则汇总采集条数、刷新流程统计。</li>
 * </ol>
 *
 * <p>数据流约定：节点输出是「行列表」，下游收集所有上游输出作为输入；
 * 条件节点通过 handle 标记分支，与 handle 不匹配的连线会被跳过。</p>
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionRepository executions;
    private final FlowRepository flowsRepo;
    private final MetaRepository meta;
    private final FlowService flowService;
    private final NodeExecutorRegistry executorRegistry;
    private final ExecutionQueue queue;
    private final ExecutionEventBus bus;
    private final CryptoService crypto;

    public ExecutionService(ExecutionRepository executions, FlowRepository flowsRepo,
                            MetaRepository meta, FlowService flowService,
                            NodeExecutorRegistry executorRegistry, ExecutionQueue queue,
                            ExecutionEventBus bus, CryptoService crypto) {
        this.executions = executions;
        this.flowsRepo = flowsRepo;
        this.meta = meta;
        this.flowService = flowService;
        this.executorRegistry = executorRegistry;
        this.queue = queue;
        this.bus = bus;
        this.crypto = crypto;
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
     * @param triggerType    manual / schedule
     * @param idempotencyKey 幂等键，可为 null
     * @return 已落库的 PENDING 记录
     */
    public Execution submit(String flowId, String triggerType, String idempotencyKey) {
        Flow flow = flowService.get(flowId);
        String id = IdUtil.prefix("EXEC");
        String now = now();
        Execution e = new Execution(id, flowId, flow.publishedVersion(), triggerType,
                "PENDING", now, null, 0L, null, idempotencyKey);
        executions.saveExecution(e);

        String flowName = flow.name() == null ? flow.id() : flow.name();
        writeLog(id, null, "INFO", "执行已入队，流程：" + flowName);
        bus.publish(id, "queued", Map.of("flowName", flowName));
        queue.publish(id);
        return e;
    }

    /**
     * 执行指定任务（由队列消费者调用）。
     *
     * @param executionId 执行 ID
     */
    public void execute(String executionId) {
        Execution execution = executions.execution(executionId);
        if (execution == null || "SUCCESS".equals(execution.status()) || "FAILED".equals(execution.status())) {
            return;
        }
        Flow flow = flowsRepo.flow(execution.flowId());
        if (flow == null) {
            markFailed(execution, "流程不存在：" + execution.flowId());
            return;
        }
        updateStatus(execution, "RUNNING", null, 0L);
        writeLog(executionId, null, "INFO", "开始执行流程 " + flow.name());
        bus.publish(executionId, "started", Map.of("flowId", flow.id()));

        try {
            // 已发布流程只执行发布快照；未发布的草稿只在用户手动点「运行」时使用
            boolean published = "active".equals(flow.status())
                    && flow.publishedGraphJson() != null && !flow.publishedGraphJson().isBlank();
            String graphSource = published ? flow.publishedGraphJson() : flow.graphJson();
            FlowGraph graph = JsonUtil.parse(graphSource, FlowGraph.class);
            List<GraphNode> ordered = flowService.topologicalOrder(graph);

            // 预先把入边按目标节点分组，避免每个节点都遍历全部连线
            Map<String, List<GraphEdge>> incoming = new HashMap<>();
            for (GraphEdge edge : graph.connections()) {
                incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            }

            Map<String, List<Map<String, Object>>> outputs = new HashMap<>();
            Map<String, String> handles = new HashMap<>();
            Map<String, Object> vars = loadVariables();
            long records = 0L;

            for (GraphNode node : ordered) {
                long start = System.currentTimeMillis();
                List<Map<String, Object>> input = collectInput(node, incoming, outputs, handles);
                String title = node.title() == null ? node.type() : node.title();

                writeLog(executionId, node.id(), "INFO", "节点开始：" + title);
                bus.publish(executionId, "node-start", Map.of("nodeId", node.id(), "title", title));

                try {
                    NodeExecutor executor = executorRegistry.get(node);
                    NodeExecutor.ExecResult result = executor.execute(
                            new NodeExecutor.ExecContext(executionId, flow.id(), node, vars),
                            node.config() == null ? Map.of() : node.config(),
                            input);

                    List<Map<String, Object>> output = result.output() == null ? List.of() : result.output();
                    outputs.put(node.id(), output);
                    if (result.handle() != null) {
                        handles.put(node.id(), result.handle());
                    }

                    long duration = System.currentTimeMillis() - start;
                    executions.saveNode(new ExecutionNode(IdUtil.next(), executionId, node.id(),
                            title, node.type(), node.nodeVersion(), "SUCCESS", duration,
                            JsonUtil.stringify(input), JsonUtil.stringify(output), null, 0));
                    // 代码节点的 console.log / print 等输出直接出现在执行日志黑框中
                    if (result.logs() != null) {
                        result.logs().forEach(line -> writeLog(executionId, node.id(), "INFO", line));
                    }
                    writeLog(executionId, node.id(), "INFO",
                            "节点完成：" + title + "，输出 " + output.size() + " 条，耗时 " + duration + "ms");
                    bus.publish(executionId, "node-success", Map.of("nodeId", node.id(), "count", output.size()));

                    // 只有采集类与输出类节点贡献「采集条数」指标，逻辑节点不算
                    if ("source".equals(node.type()) || "output".equals(node.type())) {
                        records += output.size();
                    }
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
     * <p>跳过两类连线：上游根本没产出（如未执行的分支），
     * 以及条件节点的分支标记与实际走向不一致的连线。</p>
     */
    private List<Map<String, Object>> collectInput(GraphNode node, Map<String, List<GraphEdge>> incoming,
                                                   Map<String, List<Map<String, Object>>> outputs,
                                                   Map<String, String> handles) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (GraphEdge edge : incoming.getOrDefault(node.id(), List.of())) {
            List<Map<String, Object>> upstream = outputs.get(edge.source());
            if (upstream == null) {
                continue;
            }
            String declared = edge.sourceHandle();
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

    /** 加载已解密变量，供表达式与代码节点引用。 */
    private Map<String, Object> loadVariables() {
        Map<String, Object> vars = new LinkedHashMap<>();
        meta.variables().forEach(v -> {
            try {
                vars.put(v.key(), crypto.decrypt(v.valueCipher()));
            } catch (Exception e) {
                vars.put(v.key(), "");
            }
        });
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

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }
}
