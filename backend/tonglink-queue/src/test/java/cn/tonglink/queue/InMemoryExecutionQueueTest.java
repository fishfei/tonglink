/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryExecutionQueueTest {

    private InMemoryExecutionQueue queue(int consumers, int capacity, long offerTimeoutMs) {
        return new InMemoryExecutionQueue(consumers, capacity, offerTimeoutMs);
    }

    @Test
    void 配置越界会被纠正到合法范围() {
        InMemoryExecutionQueue queue = queue(0, 0, 0);
        assertThat(queue.consumerCount()).isEqualTo(1);
        assertThat(queue.capacity()).isGreaterThanOrEqualTo(16);
        queue.shutdown();

        InMemoryExecutionQueue large = queue(999, 9_999_999, 0);
        assertThat(large.consumerCount()).isEqualTo(32);
        assertThat(large.capacity()).isEqualTo(1_000_000);
        large.shutdown();
    }

    @Test
    void 队列满时明确拒绝入队() {
        int capacity = 16;
        InMemoryExecutionQueue queue = queue(1, capacity, 20);
        try {
            for (int i = 0; i < capacity; i++) {
                queue.publish("task-" + i);
            }
            assertThatThrownBy(() -> queue.publish("overflow"))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("队列已满");
            assertThat(queue.queueDepth()).isEqualTo(capacity);
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void 多个消费者并行处理任务() throws Exception {
        InMemoryExecutionQueue queue = queue(3, 100, 100);
        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(6);
        try {
            queue.consume(id -> {
                processed.add(id);
                latch.countDown();
            });
            for (int i = 0; i < 6; i++) {
                queue.publish("exec-" + i);
            }
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(processed).hasSize(6);
            assertThat(queue.runningCount()).isZero();
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void 单个任务异常不会杀死消费者() throws Exception {
        InMemoryExecutionQueue queue = queue(1, 100, 100);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        try {
            queue.consume(id -> {
                calls.incrementAndGet();
                latch.countDown();
                if ("bad".equals(id)) {
                    throw new IllegalStateException("模拟任务异常");
                }
            });
            queue.publish("bad");
            queue.publish("good");
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(queue.runningCount()).isZero();
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void 重复注册消费者不会翻倍() throws Exception {
        InMemoryExecutionQueue queue = queue(2, 100, 100);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            queue.consume(id -> {
                calls.incrementAndGet();
                latch.countDown();
            });
            queue.consume(id -> calls.addAndGet(100));
            queue.publish("only-once");
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            queue.shutdown();
        }
    }

    @Test
    void 关闭后拒绝入队() {
        InMemoryExecutionQueue queue = queue(1, 16, 10);
        queue.shutdown();
        assertThatThrownBy(() -> queue.publish("after-close"))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("已关闭");
    }
}
