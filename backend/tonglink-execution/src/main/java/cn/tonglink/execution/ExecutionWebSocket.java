/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 执行实时推送 WebSocket。
 *
 * <p>连接地址 {@code /ws/executions/{executionId}}，服务端把节点状态与日志事件
 * 以 JSON 帧推给前端；断线重连后可通过 REST 的 {@code after=seq} 补齐遗漏日志。</p>
 */
@Configuration
@EnableWebSocket
public class ExecutionWebSocket implements WebSocketConfigurer {

    private final ExecutionEventBus bus;

    public ExecutionWebSocket(ExecutionEventBus bus) {
        this.bus = bus;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(bus), "/ws/executions/{executionId}")
                .setAllowedOriginPatterns("*");
    }

    /**
     * 单连接处理器：按 executionId 订阅，断开时清理订阅避免内存泄漏。
     */
    static class Handler extends TextWebSocketHandler {

        private final ExecutionEventBus bus;
        private final Map<WebSocketSession, Consumer<String>> subscriptions = new ConcurrentHashMap<>();

        Handler(ExecutionEventBus bus) {
            this.bus = bus;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            String uri = String.valueOf(session.getUri());
            String executionId = uri.substring(uri.lastIndexOf('/') + 1);
            Consumer<String> listener = message -> {
                try {
                    if (session.isOpen()) {
                        // WebSocketSession 不是线程安全的，发送需串行
                        synchronized (session) {
                            session.sendMessage(new TextMessage(message));
                        }
                    }
                } catch (Exception ignored) {
                    // 连接已关闭等情况忽略即可
                }
            };
            subscriptions.put(session, listener);
            bus.subscribe(executionId, listener);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            Consumer<String> listener = subscriptions.remove(session);
            if (listener != null) {
                bus.subscriptions().values().forEach(set -> set.remove(listener));
            }
        }
    }
}
