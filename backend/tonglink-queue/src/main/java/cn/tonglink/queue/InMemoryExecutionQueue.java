/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内执行队列（默认实现）。
 *
 * <p>基于 {@link LinkedBlockingQueue}，消费者线程阻塞等待而不是轮询，空闲时零 CPU 开销。
 * 默认 2 个消费线程，允许两条流程并行执行；单条流程内部仍是串行拓扑执行。</p>
 *
 * <p>局限：进程重启会丢失队列中的任务。对定时任务影响不大（下一周期会重新触发），
 * 对人工触发的任务则需重跑；需要可靠投递时请切换 {@code TONGLINK_QUEUE_TYPE=redis}。</p>
 */
@Component
@ConditionalOnProperty(name = "tonglink.queue.type", havingValue = "inmemory", matchIfMissing = true)
public class InMemoryExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionQueue.class);

    /** 并行消费线程数。 */
    private static final int CONSUMERS = 2;

    private final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(CONSUMERS, new NamedFactory());
    private final AtomicInteger working = new AtomicInteger();

    @Override
    public void publish(String executionId) {
        pending.offer(executionId);
    }

    @Override
    public void consume(Consumer<String> handler) {
        for (int i = 0; i < CONSUMERS; i++) {
            executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
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
                    } catch (Exception e) {
                        // 单次执行失败不能拖垮消费线程，异常信息已由执行服务写入日志
                        log.error("执行任务处理失败：{}", id, e);
                    } finally {
                        working.decrementAndGet();
                    }
                }
            });
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

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 给线程起可读名字，方便 jstack 定位。 */
    private static final class NamedFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tonglink-exec-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
