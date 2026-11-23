/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.flow;

import java.util.List;
import java.util.Map;

/**
 * 流程图：前后端与节点插件共享的契约。
 *
 * <p>直接对应前端 {@code graphPayload()} 的结构。使用 record 而非 Map 的好处是
 * 环检测、拓扑排序等逻辑有明确的字段可依，不必到处强转。</p>
 *
 * @param schemaVersion 契约版本，用于未来兼容升级
 * @param id            流程 ID（导入时会被重写）
 * @param name          流程名称
 * @param projectId     所属项目
 * @param settings      流程级设置：并发、超时、重叠策略
 * @param nodes         节点列表
 * @param connections   连线列表
 */
public record FlowGraph(int schemaVersion, String id, String name, String projectId,
                        Map<String, Object> settings, List<GraphNode> nodes,
                        List<GraphEdge> connections) {

    /** 紧凑构造器：把 null 归一成空集合，避免下游反复判空。 */
    public FlowGraph {
        if (settings == null) {
            settings = Map.of();
        }
        if (nodes == null) {
            nodes = List.of();
        }
        if (connections == null) {
            connections = List.of();
        }
    }
}
