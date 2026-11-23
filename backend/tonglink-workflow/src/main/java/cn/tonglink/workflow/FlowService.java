/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.workflow;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.FlowVersion;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.node.NodeRegistry;
import cn.tonglink.repository.FlowRepository;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 流程业务逻辑：CRUD、DAG 校验、拓扑排序、版本发布、导入。
 *
 * <p>设计取舍：草稿允许不完整（用户可能还没拖完节点），因此保存只做「结构校验」；
 * 真正阻断的是发布——发布后的流程会被定时任务执行，必须保证图合法。</p>
 */
@Service
public class FlowService {

    private final FlowRepository repo;
    private final NodeRegistry registry;

    public FlowService(FlowRepository repo, NodeRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    /** 按项目过滤流程；{@code projectId} 为空时返回全部。 */
    public List<Flow> list(String projectId) {
        return projectId == null || projectId.isBlank() ? repo.flows() : repo.flowsByProject(projectId);
    }

    /**
     * 取单个流程。
     *
     * @throws ApiException 不存在时返回 404
     */
    public Flow get(String id) {
        Flow f = repo.flow(id);
        if (f == null) {
            throw new ApiException(Codes.NOT_FOUND, "流程不存在");
        }
        return f;
    }

    /**
     * 新建流程。
     *
     * <p>兼容三种入参：{@code graph}（对象）、{@code graphJson}（字符串）、都不传（空图）。
     * 早期版本忽略了 graph 字段导致「创建后图丢失」，这里显式处理。</p>
     */
    public Flow create(Map<String, Object> body) {
        String projectId = str(body.get("projectId"), "");
        if (projectId.isBlank()) {
            throw new ApiException(Codes.VALIDATION, "请先选择项目");
        }
        String now = now();
        String id = str(body.get("id"), IdUtil.prefix("FLOW"));
        String name = str(body.get("name"), "未命名流程");
        String graphJson = resolveGraphJson(body, id, name, projectId, true);
        Flow f = new Flow(id, projectId, name, str(body.get("description"), ""), "paused",
                graphJson, 0, "—", "—", 0L, "—", now, now);
        repo.saveFlow(f);
        return f;
    }

    /**
     * 更新流程。保存草稿不校验图结构，保证编辑过程可随时落盘。
     */
    public Flow update(String id, Map<String, Object> body) {
        Flow old = get(id);
        // 只改名称/状态时不应把已有图覆盖成空图，因此这里不允许生成空图
        String graphJson = resolveGraphJson(body, old.id(), old.name(), old.projectId(), false);
        if (graphJson == null) {
            graphJson = old.graphJson();
        }
        Flow f = new Flow(id,
                str(body.get("projectId"), old.projectId()),
                str(body.get("name"), old.name()),
                str(body.get("description"), nullToEmpty(old.description())),
                str(body.get("status"), old.status()),
                graphJson,
                // 编辑草稿绝不覆盖已发布快照：定时任务必须继续跑发布时的图
                old.publishedGraphJson(),
                intOf(body.get("publishedVersion"), old.publishedVersion()),
                str(body.get("nextRun"), nullToEmpty(old.nextRun())),
                str(body.get("lastRun"), nullToEmpty(old.lastRun())),
                old.records(),
                str(body.get("successRate"), nullToEmpty(old.successRate())),
                old.createdAt(),
                now());
        repo.updateFlow(f);
        return f;
    }

    /** 删除流程。 */
    public void delete(String id) {
        get(id);
        repo.deleteFlow(id);
    }

    /**
     * 发布新版本：校验图结构后生成不可变快照，并把流程置为 active。
     *
     * @return 新版本记录
     * @throws ApiException 图非法时返回 422，消息中列出全部问题
     */
    public FlowVersion publish(String id) {
        Flow f = get(id);
        FlowGraph graph = JsonUtil.parse(f.graphJson(), FlowGraph.class);
        assertValid(graph);
        int version = f.publishedVersion() + 1;
        String now = now();
        FlowVersion v = new FlowVersion(IdUtil.next(), id, version, f.graphJson(),
                "发布版本 " + version, now);
        repo.saveVersion(v);
        repo.updateFlow(new Flow(f.id(), f.projectId(), f.name(), f.description(), "active",
                f.graphJson(), f.graphJson(), version, f.nextRun(), f.lastRun(), f.records(),
                f.successRate(), f.createdAt(), now));
        return v;
    }

    /**
     * 下线流程：置为 paused 并清空下次调度时间，调度器立即停止自动触发。
     *
     * <p>发布快照与版本历史保留，便于审计；再次发布会在原版本号上递增。
     * 已下线流程仍可从页面手动点「运行」来验证草稿。</p>
     */
    public Flow unpublish(String id) {
        Flow f = get(id);
        // 只有当前处于发布态的流程才允许下线；草稿/已下线流程重复下线没有意义
        if (!"active".equals(f.status()) || f.publishedVersion() <= 0) {
            throw new ApiException(Codes.VALIDATION, "流程尚未发布，无法下线");
        }
        Flow updated = new Flow(f.id(), f.projectId(), f.name(), f.description(), "paused",
                f.graphJson(), f.publishedGraphJson(), f.publishedVersion(), "—", f.lastRun(),
                f.records(), f.successRate(), f.createdAt(), now());
        repo.updateFlow(updated);
        return updated;
    }

    /** 版本历史，最新在前。 */
    public List<FlowVersion> versions(String id) {
        return repo.versions(id);
    }

    /**
     * 校验图结构，返回全部问题（不抛异常，供预览与测试使用）。
     *
     * @return 空列表表示合法
     */
    public List<String> validateErrors(FlowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph == null || graph.nodes().isEmpty()) {
            return List.of("流程至少需要一个节点");
        }
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (GraphNode n : graph.nodes()) {
            if (nodes.containsKey(n.id())) {
                errors.add("重复节点 ID：" + n.id());
            }
            nodes.put(n.id(), n);
            try {
                registry.definition(n.type(), n.subtype());
            } catch (ApiException e) {
                errors.add("未安装的节点：" + n.type() + ":" + n.subtype());
            }
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphEdge e : graph.connections()) {
            if (!nodes.containsKey(e.source()) || !nodes.containsKey(e.target())) {
                errors.add("连线端点不存在：" + e.id());
                continue;
            }
            adjacency.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        }
        if (hasCycle(nodes.keySet(), adjacency)) {
            errors.add("流程中存在环路，无法执行");
        }
        return errors;
    }

    /**
     * 校验并在不合法时抛出异常。
     *
     * @throws ApiException 422，消息为全部问题的分号拼接
     */
    public void assertValid(FlowGraph graph) {
        List<String> errors = validateErrors(graph);
        if (!errors.isEmpty()) {
            throw new ApiException(Codes.VALIDATION, String.join("；", errors));
        }
    }

    /**
     * 拓扑排序：执行引擎按此顺序逐个运行节点。
     *
     * @throws ApiException 图非法时返回 422
     */
    public List<GraphNode> topologicalOrder(FlowGraph graph) {
        assertValid(graph);
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(n -> nodes.put(n.id(), n));

        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> indegree.put(id, 0));
        for (GraphEdge e : graph.connections()) {
            adjacency.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            indegree.merge(e.target(), 1, Integer::sum);
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        List<GraphNode> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ordered.add(nodes.get(current));
            for (String next : adjacency.getOrDefault(current, List.of())) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) {
                    queue.add(next);
                }
            }
        }
        return ordered;
    }

    /**
     * 导入流程 JSON：所有节点与连线都重新生成 ID，避免与现有流程冲突。
     *
     * @param payload 兼容 {@code {flow:{...}}} 与 {@code {item:{...}}} 两种导出格式
     */
    public Flow importFlow(Map<String, Object> payload) {
        Object raw = payload.get("flow") != null ? payload.get("flow") : payload.get("item");
        if (raw == null) {
            throw new ApiException(Codes.VALIDATION, "导入文件缺少 flow 字段");
        }
        FlowGraph graph = JsonUtil.parse(JsonUtil.stringify(raw), FlowGraph.class);
        if (graph == null) {
            throw new ApiException(Codes.VALIDATION, "导入文件不是合法的流程图");
        }
        String projectId = str(payload.get("projectId"), nullToEmpty(graph.projectId()));

        // 节点 ID 全量重映射，连线跟着改，保证导入多次也不冲突
        Map<String, String> idMap = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode n : graph.nodes()) {
            String newId = "n-" + IdUtil.next();
            idMap.put(n.id(), newId);
            nodes.add(new GraphNode(newId, n.type(), n.subtype(), n.nodeVersion(), n.title(),
                    n.position(), n.config()));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge e : graph.connections()) {
            edges.add(new GraphEdge("e-" + IdUtil.next(),
                    idMap.getOrDefault(e.source(), e.source()),
                    idMap.getOrDefault(e.target(), e.target()),
                    e.sourceHandle(), e.targetHandle()));
        }

        String flowId = IdUtil.prefix("FLOW");
        String name = nullToEmpty(graph.name()) + "（导入）";
        FlowGraph imported = new FlowGraph(1, flowId, name, projectId, graph.settings(), nodes, edges);
        String now = now();
        Flow flow = new Flow(flowId, projectId, name, "由 JSON 导入", "paused",
                JsonUtil.stringify(imported), 0, "—", "—", 0L, "—", now, now);
        repo.saveFlow(flow);
        return flow;
    }

    // ---------------- 内部工具 ----------------

    /**
     * 从请求体解析图 JSON。
     *
     * @param allowEmpty 允许返回空图（新建场景）；更新场景必须原样保留旧图
     * @return 图 JSON；无图且不允许空图时返回 null
     */
    private String resolveGraphJson(Map<String, Object> body, String id, String name, String projectId,
                                    boolean allowEmpty) {
        if (body.get("graph") != null) {
            return JsonUtil.stringify(body.get("graph"));
        }
        if (body.get("graphJson") != null && !String.valueOf(body.get("graphJson")).isBlank()) {
            return String.valueOf(body.get("graphJson"));
        }
        if (!allowEmpty) {
            return null;
        }
        return JsonUtil.stringify(new FlowGraph(1, id, name, projectId, Map.of(), List.of(), List.of()));
    }

    /** Kahn 算法检测有向环。 */
    private boolean hasCycle(Set<String> nodes, Map<String, List<String>> adjacency) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.forEach(n -> indegree.put(n, 0));
        adjacency.values().forEach(list -> list.forEach(t -> indegree.merge(t, 1, Integer::sum)));
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited.add(current);
            for (String next : adjacency.getOrDefault(current, List.of())) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) {
                    queue.add(next);
                }
            }
        }
        return visited.size() != nodes.size();
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int intOf(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
