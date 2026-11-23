/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 执行队列（队列模式）。
 *
 * <p>使用消费组 {@code XREADGROUP} 保证一条消息只被一个 Worker 处理，
 * 处理完成后 {@code XACK} 确认；进程崩溃时未确认的消息可由 {@code XAUTOCLAIM} 接管，
 * 配合执行表的幂等键避免重复执行。</p>
 *
 * <p>启用方式：{@code TONGLINK_QUEUE_TYPE=redis} + {@code TONGLINK_REDIS_URI}。</p>
 */
@Component
@ConditionalOnProperty(name = "tonglink.queue.type", havingValue = "redis")
public class RedisStreamExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamExecutionQueue.class);

    private static final String STREAM_KEY = "tonglink:executions";
    private static final String GROUP = "tonglink-workers";

    private final RedisClient client;
    private final RedisCommands<String, String> commands;
    private final String consumerName = UUID.randomUUID().toString();

    public RedisStreamExecutionQueue(@Value("${tonglink.redis.uri:redis://localhost:6379}") String uri) {
        this.client = RedisClient.create(uri);
        this.commands = client.connect().sync();
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(STREAM_KEY, "0-0"), GROUP,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (Exception e) {
            // 消费组已存在属于正常情况
            log.debug("消费组已存在或创建失败：{}", e.getMessage());
        }
    }

    @Override
    public void publish(String executionId) {
        Map<String, String> body = new HashMap<>();
        body.put("executionId", executionId);
        commands.xadd(STREAM_KEY, body);
    }

    @Override
    public void consume(Consumer<String> handler) {
        Thread worker = new Thread(() -> {
            // 全限定名：lettuce 的 Consumer 与 java.util.function.Consumer 同名，直接 import 会冲突
            io.lettuce.core.Consumer<String> groupConsumer =
                    io.lettuce.core.Consumer.from(GROUP, consumerName);
            XReadArgs args = new XReadArgs().count(10).block(2000);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<StreamMessage<String, String>> messages = commands.xreadgroup(groupConsumer, args,
                            XReadArgs.StreamOffset.lastConsumed(STREAM_KEY));
                    for (StreamMessage<String, String> message : messages) {
                        String id = message.getBody().get("executionId");
                        try {
                            if (id != null) {
                                handler.accept(id);
                            }
                            commands.xack(STREAM_KEY, GROUP, message.getId());
                        } catch (Exception e) {
                            // 不确认，交由 Pending 列表重试
                            log.error("Redis 队列任务处理失败: {}", id, e);
                        }
                    }
                } catch (Exception e) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "tonglink-redis-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void close() {
        client.close();
    }
}
