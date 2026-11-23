/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tonglink.common.ApiException;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.node.NodeRegistry;
import cn.tonglink.repository.FlowRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 流程服务测试：CRUD 建图、DAG 环检测、拓扑排序。
 *
 * <p>节点注册中心用真实实现（读取 classpath 的 node-definitions.json），
 * 仓库用 Mockito 桩，这样测试聚焦在图算法本身，不依赖数据库。</p>
 */
class FlowServiceTest {

    private FlowRepository repo;
    private FlowService service;

    @BeforeEach
    void setUp() {
        repo = mock(FlowRepository.class);
        when(repo.nodePackages()).thenReturn(List.of());
        NodeRegistry registry = new NodeRegistry(repo);
        service = new FlowService(repo, registry);
    }

    /** 创建流程时必须原样保存传入的 graph，否则画布会空白（历史缺陷回归）。 */
    @Test
    void 创建流程保留传入的图结构() {
        Map<String, Object> graph = Map.of(
                "schemaVersion", 1,
                "nodes", List.of(Map.of(
                        "id", "n1", "type", "trigger", "subtype", "manual",
                        "title", "手动触发", "position", Map.of("x", 10, "y", 20),
                        "config", Map.of())),
                "connections", List.of());

        Flow created = service.create(Map.of(
                "name", "测试流程", "projectId", "P-001", "graph", graph));

        ArgumentCaptor<Flow> captor = ArgumentCaptor.forClass(Flow.class);
        verify(repo).saveFlow(captor.capture());
        assertThat(created.graphJson()).contains("n1").contains("manual");
        assertThat(captor.getValue().graphJson()).isEqualTo(created.graphJson());
        assertThat(created.publishedVersion()).isZero();
    }

    /** 存在环路时必须报错，否则执行引擎会陷入死循环。 */
    @Test
    void 环检测识别非法图() {
        FlowGraph cyclic = new FlowGraph(1, "F1", "环", "P-001", Map.of(),
                List.of(node("n1", "trigger", "manual"), node("n2", "code", "nodejs")),
                List.of(edge("e1", "n1", "n2"), edge("e2", "n2", "n1")));

        assertThat(service.validateErrors(cyclic)).anyMatch(e -> e.contains("环路"));
        assertThatThrownBy(() -> service.topologicalOrder(cyclic))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("环路");
    }

    /** 拓扑排序必须满足「上游先于下游」。 */
    @Test
    void 拓扑排序保证上游先执行() {
        FlowGraph graph = new FlowGraph(1, "F2", "链", "P-001", Map.of(),
                List.of(node("n3", "output", "timeseries"),
                        node("n1", "trigger", "manual"),
                        node("n2", "code", "nodejs")),
                List.of(edge("e1", "n1", "n2"), edge("e2", "n2", "n3")));

        List<GraphNode> ordered = service.topologicalOrder(graph);
        assertThat(ordered).extracting(GraphNode::id).containsExactly("n1", "n2", "n3");
    }

    private GraphNode node(String id, String type, String subtype) {
        return new GraphNode(id, type, subtype, "1", id, Map.of("x", 0, "y", 0), Map.of());
    }

    private GraphEdge edge(String id, String source, String target) {
        return new GraphEdge(id, source, target, "out", "in");
    }
}
