/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.execution;

import cn.tonglink.common.JsonUtil;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 进程内执行事件总线。
 *
 * <p>执行过程中的节点状态、日志、终态通过这里推送给 WebSocket 订阅者，
 * 前端因此能实时看到节点逐个高亮。跨实例场景应替换为 Redis Pub/Sub，
 * 当前实现面向单实例轻量部署。</p>
 */
@Component
public class ExecutionEventBus {

    /** executionId -> 订阅者集合。使用写时复制集合，发布时不阻塞订阅。 */
    private final Map<String, Set<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    /** 订阅某次执行的事件。 */
    public void subscribe(String executionId, Consumer<String> listener) {
        listeners.computeIfAbsent(executionId, k -> new CopyOnWriteArraySet<>()).add(listener);
    }

    /** 取消订阅。 */
    public void unsubscribe(String executionId, Consumer<String> listener) {
        Set<Consumer<String>> set = listeners.get(executionId);
        if (set != null) {
            set.remove(listener);
        }
    }

    /** 当前订阅关系，断连清理时使用。 */
    public Map<String, Set<Consumer<String>>> subscriptions() {
        return listeners;
    }

    /**
     * 发布事件。
     *
     * @param executionId 执行 ID
     * @param event       事件名：queued / started / node-start / node-success / node-failed / log / success / failed
     * @param payload     事件负载
     */
    public void publish(String executionId, String event, Object payload) {
        Set<Consumer<String>> set = listeners.get(executionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String message = JsonUtil.stringify(Map.of(
                "event", event,
                "executionId", executionId,
                "data", payload));
        for (Consumer<String> listener : set) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                // 单个订阅者异常不能影响其他订阅者
            }
        }
    }
}
