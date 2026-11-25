/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

/**
 * 延时节点向执行引擎发出的「暂停本次执行」信号。
 *
 * <p>执行器本身不持有线程、也不做 Thread.sleep。它只计算到期时间并抛出该信号，
 * 由引擎把当前进度持久化到 {@code execution_wait} 表后释放工作线程；
 * 到期后调度器重新投递执行 ID，引擎从断点继续。这样长时间等待不会占满队列消费者，
 * 应用重启后也能按原计划恢复。</p>
 *
 * @param waitMillis 需要等待的毫秒数
 * @param reason     写入执行日志的说明
 */
public class WaitSignal extends RuntimeException {

    private final long waitMillis;

    public WaitSignal(long waitMillis, String reason) {
        super(reason);
        this.waitMillis = waitMillis;
    }

    public long waitMillis() {
        return waitMillis;
    }
}
