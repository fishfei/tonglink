/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.queue;

import java.util.function.Consumer;

/**
 * 执行队列抽象。
 *
 * <p>上层只关心「投递一个执行 ID」和「注册消费者」，具体是进程内队列还是 Redis Stream
 * 由配置决定。这样轻量模式（单容器）与队列模式（多 Worker）共用同一套执行逻辑。</p>
 */
public interface ExecutionQueue {

    /**
     * 投递执行任务。
     *
     * @param executionId 执行 ID，消费方据此从数据库加载完整上下文
     */
    void publish(String executionId);

    /**
     * 注册消费者，由实现负责无限循环拉取。
     *
     * @param handler 处理逻辑，异常应自行捕获，不能中断消费循环
     */
    void consume(Consumer<String> handler);
}
