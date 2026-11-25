/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 执行队列（队列模式）。
 *
 * <p>使用消费组 {@code XREADGROUP} 保证一条消息只被一个 Worker 处理，处理完成后 {@code XACK}；
 * 进程崩溃时未确认的消息由 {@code XAUTOCLAIM} 在超过空闲阈值后认领重投，
 * 配合执行表的幂等判断（终态不再重跑）避免重复执行。</p>
 *
 * <p>并发模型：{@code consumers} 个常驻线程从线程池执行，每个消费者使用独立连接做阻塞读，
 * 发布与确认共用另一条非阻塞连接，避免 {@code XREAD BLOCK} 占住连接拖慢发布。</p>
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
    /** 发布 / ACK / 认领使用的非阻塞连接。 */
    private final StatefulRedisConnection<String, String> commandConnection;
    private final RedisCommands<String, String> commands;
    private final List<StatefulRedisConnection<String, String>> consumerConnections =
            new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor executor;
    private final int consumers;
    private final long blockMs;
    private final int batchSize;
    private final long claimIdleMs;
    private final long claimIntervalMs;
    private final String consumerPrefix = UUID.randomUUID().toString();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> handler;

    public RedisStreamExecutionQueue(
            @Value("${tonglink.redis.uri:redis://localhost:6379}") String uri,
            @Value("${tonglink.queue.redis.consumers:2}") int consumers,
            @Value("${tonglink.queue.redis.block-ms:2000}") long blockMs,
            @Value("${tonglink.queue.redis.batch-size:10}") int batchSize,
            @Value("${tonglink.queue.redis.claim-idle-ms:300000}") long claimIdleMs,
            @Value("${tonglink.queue.redis.claim-interval-ms:60000}") long claimIntervalMs) {
        this.consumers = clamp(consumers, 1, 64, "tonglink.queue.redis.consumers");
        this.blockMs = Math.max(100L, blockMs);
        this.batchSize = clamp(batchSize, 1, 100, "tonglink.queue.redis.batch-size");
        this.claimIdleMs = Math.max(10_000L, claimIdleMs);
        this.claimIntervalMs = Math.max(5_000L, claimIntervalMs);

        this.client = RedisClient.create(uri);
        this.commandConnection = client.connect();
        this.commands = commandConnection.sync();
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(STREAM_KEY, "0-0"), GROUP,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (Exception e) {
            // 消费组已存在属于正常情况
            log.debug("消费组已存在或创建失败：{}", e.getMessage());
        }

        this.executor = new ThreadPoolExecutor(
                this.consumers, this.consumers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.consumers),
                new NamedFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(false);
    }

    @Override
    public void publish(String executionId) {
        if (closed.get()) {
            throw new RejectedExecutionException("Redis 队列已关闭，拒绝入队：" + executionId);
        }
        Map<String, String> body = new HashMap<>();
        body.put("executionId", executionId);
        commands.xadd(STREAM_KEY, body);
    }

    @Override
    public void consume(Consumer<String> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("消费者不能为空");
        }
        if (!started.compareAndSet(false, true)) {
            log.warn("Redis 队列消费者已注册，忽略重复注册");
            return;
        }
        this.handler = handler;
        for (int i = 0; i < consumers; i++) {
            startWorker(i);
        }
        log.info("Redis 执行队列启动：消费者 {} 个，批量 {}，阻塞 {}ms，认领空闲 {}ms",
                consumers, batchSize, blockMs, claimIdleMs);
    }

    private void startWorker(int index) {
        if (closed.get()) {
            return;
        }
        try {
            executor.execute(() -> workerLoop(index));
        } catch (RejectedExecutionException e) {
            log.error("Redis 执行消费者 {} 启动失败", index, e);
        }
    }

    private void workerLoop(int index) {
        // 阻塞读必须使用独立连接，否则 XREAD BLOCK 会占住发布/确认共用的连接
        StatefulRedisConnection<String, String> connection = null;
        try {
            connection = client.connect();
            consumerConnections.add(connection);
            RedisCommands<String, String> readCommands = connection.sync();
            io.lettuce.core.Consumer<String> groupConsumer =
                    io.lettuce.core.Consumer.from(GROUP, consumerPrefix + "-" + index);
            XReadArgs args = new XReadArgs().count(batchSize).block(blockMs);
            long lastClaim = System.currentTimeMillis();

            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    List<StreamMessage<String, String>> messages = readCommands.xreadgroup(
                            groupConsumer, args,
                            XReadArgs.StreamOffset.lastConsumed(STREAM_KEY));
                    for (StreamMessage<String, String> message : messages) {
                        if (!handle(message, groupConsumer)) {
                            break;
                        }
                    }
                    // 只由 0 号消费者执行认领，避免多个消费者同时认领同一批消息
                    if (index == 0 && System.currentTimeMillis() - lastClaim >= claimIntervalMs) {
                        reclaimStale(groupConsumer);
                        lastClaim = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    if (closed.get()) {
                        return;
                    }
                    log.error("Redis 队列消费者 {} 读取失败", index, e);
                    backoff();
                }
            }
        } catch (Throwable e) {
            log.error("Redis 队列消费者 {} 异常终止", index, e);
        } finally {
            if (connection != null) {
                consumerConnections.remove(connection);
                try {
                    connection.close();
                } catch (Exception e) {
                    log.debug("关闭消费者连接失败：{}", e.getMessage());
                }
            }
        }
    }

    /** 处理一条消息并确认；失败时保留在 PEL 中等待认领重试。 */
    private boolean handle(StreamMessage<String, String> message,
                           io.lettuce.core.Consumer<String> groupConsumer) {
        if (closed.get()) {
            return false;
        }
        String id = message.getBody().get("executionId");
        try {
            if (id != null) {
                handler.accept(id);
            }
            commands.xack(STREAM_KEY, GROUP, message.getId());
            return true;
        } catch (Throwable e) {
            // 不确认，交由 XAUTOCLAIM 认领重试
            log.error("Redis 队列任务处理失败: {}", id, e);
            return true;
        }
    }

    /** 认领超时未确认的消息，避免消费者崩溃后消息永久留在 PEL。 */
    private void reclaimStale(io.lettuce.core.Consumer<String> groupConsumer) {
        try {
            XAutoClaimArgs<String> args = XAutoClaimArgs.Builder
                    .xautoclaim(groupConsumer, claimIdleMs, "0-0")
                    .count(batchSize);
            ClaimedMessages<String, String> claimed = commands.xautoclaim(STREAM_KEY, args);
            if (claimed == null || claimed.getMessages() == null) {
                return;
            }
            for (StreamMessage<String, String> message : claimed.getMessages()) {
                if (!handle(message, groupConsumer)) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("认领 Redis 超时消息失败：{}", e.getMessage());
        }
    }

    private void backoff() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前 Stream 中未确认的消息数量，供运维观察；Redis 不可用时返回 -1。 */
    public int pendingCount() {
        try {
            Long pending = commands.xpending(STREAM_KEY, GROUP).getCount();
            return pending == null ? 0 : pending.intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    public int consumerCount() {
        return consumers;
    }

    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Redis 执行消费者未在 5 秒内退出");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (StatefulRedisConnection<String, String> connection : consumerConnections) {
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("关闭消费者连接失败：{}", e.getMessage());
            }
        }
        try {
            commandConnection.close();
        } catch (Exception e) {
            log.debug("关闭命令连接失败：{}", e.getMessage());
        }
        client.close();
    }

    private static int clamp(int value, int min, int max, String name) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(value, max));
            log.warn("{} = {} 超出合法范围 [{}, {}]，已调整为 {}", name, value, min, max, fixed);
            return fixed;
        }
        return value;
    }

    private static final class NamedFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tonglink-redis-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, error) ->
                    log.error("Redis 执行消费者线程 {} 异常终止", thread.getName(), error));
            return t;
        }
    }
}
