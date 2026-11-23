/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import java.util.List;

/**
 * 携带节点运行期日志的失败异常。
 *
 * <p>普通异常只能带一句错误消息，而代码节点失败时往往已经 print 了大量调试信息。
 * 执行引擎捕获本异常后会把这些日志逐行追加到执行日志，便于在生产环境直接定位问题。</p>
 */
public class NodeLogException extends RuntimeException {

    private final List<String> logs;

    public NodeLogException(String message, List<String> logs) {
        super(message);
        this.logs = logs == null ? List.of() : List.copyOf(logs);
    }

    /** 节点失败前产生的 stdout / stderr 日志行。 */
    public List<String> logs() {
        return logs;
    }
}
