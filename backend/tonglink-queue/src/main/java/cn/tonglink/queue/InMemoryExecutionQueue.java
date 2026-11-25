/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
 * 进程内执行队列（默认实现）。
 *
 * <p>消费者是长驻循环：线程从有界 {@link #pending} 队列阻塞取任务，空闲时零 CPU 开销。
 * 线程数、队列容量与入队等待时间均可通过配置调整，避免默认线程池的无界队列与参数不可控。</p>
 *
 * <p>局限：进程重启会丢失队列中的任务。对定时任务影响不大（下一周期会重新触发），
 * 对人工触发的任务则需重跑；需要可靠投递时请切换 {@code TONGLINK_QUEUE_TYPE=redis}。</p>
 */
@Component
@ConditionalOnProperty(name = "tonglink.queue.type", havingValue = "inmemory", matchIfMissing = true)
public class InMemoryExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionQueue.class);

    private final int consumers;
    private final int capacity;
    private final long offerTimeoutMs;
    private final BlockingQueue<String> pending;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger working = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> handler;

    public InMemoryExecutionQueue(
            @Value("${tonglink.queue.inmemory.consumers:2}") int consumers,
            @Value("${tonglink.queue.inmemory.capacity:10000}") int capacity,
            @Value("${tonglink.queue.inmemory.offer-timeout-ms:1000}") long offerTimeoutMs) {
        // 显式上下界，防止误配 0 或超大值拖垮 JVM
        this.consumers = clamp(consumers, 1, 32, "tonglink.queue.inmemory.consumers");
        this.capacity = clamp(capacity, Math.max(16, this.consumers * 4), 1_000_000,
                "tonglink.queue.inmemory.capacity");
        this.offerTimeoutMs = Math.max(0, offerTimeoutMs);

        // 有界待处理队列：生产快于消费时不再无限占用堆内存，而是明确拒绝。
        this.pending = new ArrayBlockingQueue<>(this.capacity);

        // 显式 ThreadPoolExecutor：core=max 保证常驻消费者数量固定；有界工作队列 +
        // AbortPolicy 让异常配置立刻暴露，而不是像 Executors 工厂那样静默使用无界队列。
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
            throw new RejectedExecutionException("执行队列已关闭，拒绝入队：" + executionId);
        }
        try {
            boolean accepted = offerTimeoutMs > 0
                    ? pending.offer(executionId, offerTimeoutMs, TimeUnit.MILLISECONDS)
                    : pending.offer(executionId);
            if (!accepted) {
                throw new RejectedExecutionException(
                        "执行队列已满（容量 " + capacity + "），拒绝入队：" + executionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("入队被中断：" + executionId, e);
        }
    }

    @Override
    public void consume(Consumer<String> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("消费者不能为空");
        }
        // 只允许注册一次，避免重复调用导致消费者翻倍
        if (!started.compareAndSet(false, true)) {
            log.warn("执行队列消费者已注册，忽略重复注册");
            return;
        }
        this.handler = handler;
        for (int i = 0; i < consumers; i++) {
            startWorker();
        }
        log.info("内存执行队列启动：消费者 {} 个，容量 {}，入队等待 {}ms",
                consumers, capacity, offerTimeoutMs);
    }

    private void startWorker() {
        if (closed.get()) {
            return;
        }
        try {
            // 用 execute 而非 submit：异常不会被吞进 Future，能被 UncaughtExceptionHandler 捕获
            executor.execute(this::consumeLoop);
        } catch (RejectedExecutionException e) {
            log.error("执行消费者启动失败", e);
        }
    }

    private void consumeLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            String id;
            try {
                id = pending.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            working.incrementAndGet();
            try {
                handler.accept(id);
            } catch (Throwable e) {
                // 捕获 Throwable 而非 Exception：即使 Error 也不让消费线程静默退出
                log.error("执行任务处理失败：{}", id, e);
            } finally {
                working.decrementAndGet();
            }
        }
    }

    /** 当前排队数，供健康检查与运维观察。 */
    public int queueDepth() {
        return pending.size();
    }

    /** 当前正在执行的任务数。 */
    public int runningCount() {
        return working.get();
    }

    /** 已配置的常驻消费者数量。 */
    public int consumerCount() {
        return consumers;
    }

    /** 队列容量上限。 */
    public int capacity() {
        return capacity;
    }

    @PreDestroy
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先停止接收新任务，再中断阻塞在 take() 的消费者
        executor.shutdown();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("执行消费者未在 5 秒内退出，可能存在长时间运行的任务");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int clamp(int value, int min, int max, String name) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(value, max));
            log.warn("{} = {} 超出合法范围 [{}, {}]，已调整为 {}", name, value, min, max, fixed);
            return fixed;
        }
        return value;
    }

    /** 给线程起可读名字，并为致命异常留日志，方便 jstack 定位。 */
    private static final class NamedFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tonglink-exec-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, error) ->
                    log.error("执行消费者线程 {} 异常终止", thread.getName(), error));
            return t;
        }
    }
}
