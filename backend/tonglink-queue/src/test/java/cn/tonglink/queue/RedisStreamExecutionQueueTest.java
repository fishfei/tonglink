/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Redis Stream 执行队列真机集成测试。
 *
 * <p>仅在设置 {@code TONGLINK_REDIS_URI}（如 {@code redis://192.168.3.188:6379}）后启用，
 * 未设置时整类跳过，避免普通构建依赖外部 Redis。测试使用独立前缀的 executionId，
 * 结束后从 Stream 中清理，避免污染数据。</p>
 */
@EnabledIfEnvironmentVariable(named = "TONGLINK_REDIS_URI", matches = ".+")
class RedisStreamExecutionQueueTest {

    private static final String STREAM = "tonglink:executions";
    private final String prefix = "itest-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private RedisStreamExecutionQueue queue;

    private String uri() {
        return System.getenv("TONGLINK_REDIS_URI");
    }

    private RedisStreamExecutionQueue newQueue(int consumers, long claimIdleMs, long claimIntervalMs) {
        return new RedisStreamExecutionQueue(uri(), consumers, 500, 10, claimIdleMs, claimIntervalMs);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
        // 清理本测试写入的消息（含仍在 PEL 中的），避免污染 Stream
        RedisClient client = null;
        StatefulRedisConnection<String, String> connection = null;
        try {
            client = RedisClient.create(uri());
            connection = client.connect();
            RedisCommands<String, String> commands = connection.sync();
            List<StreamMessage<String, String>> messages = commands.xrange(STREAM, Range.unbounded());
            for (StreamMessage<String, String> message : messages) {
                String id = message.getBody().get("executionId");
                if (id != null && id.startsWith(prefix)) {
                    commands.xdel(STREAM, message.getId());
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响测试结论
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void 发布后由消费组确认且每条只处理一次() throws Exception {
        queue = newQueue(3, 10_000, 5_000);
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(6);
        queue.consume(id -> {
            received.add(id);
            latch.countDown();
        });

        for (int i = 0; i < 6; i++) {
            queue.publish(prefix + i);
        }

        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500); // 等待 XACK 完成
        assertThat(received).hasSize(6);
        assertThat(new HashSet<>(received)).hasSize(6).as("消费组语义：一条消息只应被处理一次");
        assertThat(queue.pendingCount()).isZero().as("全部确认后 PEL 应为 0");
        assertThat(queue.consumerCount()).isEqualTo(3);
    }

    @Test
    void 处理失败的消息由超时认领重新投递() throws Exception {
        // claimIdleMs 最小 10000ms、claimIntervalMs 最小 5000ms，故放行 25 秒
        queue = newQueue(1, 10_000, 5_000);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        queue.consume(id -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟处理失败，保留在 PEL 等待认领");
            }
            latch.countDown();
        });

        queue.publish(prefix + "retry");

        assertThat(latch.await(25, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2).as("失败消息应被 XAUTOCLAIM 重新投递");
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    void 关闭后拒绝入队() {
        queue = newQueue(1, 10_000, 5_000);
        queue.close();
        assertThatThrownBy(() -> queue.publish(prefix + "after-close"))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("已关闭");
    }
}
