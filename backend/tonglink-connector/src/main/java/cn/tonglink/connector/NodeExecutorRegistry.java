/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.model.flow.GraphNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 执行器注册表：把 Spring 容器里的所有 {@link NodeExecutor} 按类型索引。
 *
 * <p>找不到实现时统一落到 {@link PassThroughExecutor}，这样即使节点包只提供了
 * Schema（尚无实现），流程也不会因为缺执行器直接崩溃，而是把数据透传给下游。</p>
 */
@Component
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> executors = new HashMap<>();
    private final NodeExecutor fallback;

    /**
     * @param beans    容器中全部执行器（Spring 自动注入）
     * @param fallback 兜底执行器
     */
    public NodeExecutorRegistry(List<NodeExecutor> beans, PassThroughExecutor fallback) {
        for (NodeExecutor executor : beans) {
            for (String key : executor.supports()) {
                executors.put(key, executor);
            }
        }
        this.fallback = fallback;
    }

    /** 取节点对应的执行器。 */
    public NodeExecutor get(GraphNode node) {
        return executors.getOrDefault(node.type() + ":" + node.subtype(), fallback);
    }
}
