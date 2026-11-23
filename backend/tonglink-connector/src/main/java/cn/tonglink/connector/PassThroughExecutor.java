/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 触发器执行器：产生一条「触发事件」作为流程的初始输入。
 *
 * <p>早期实现直接透传空列表，导致下游代码/逻辑节点永远拿不到数据——条件判断恒为 false、
 * 脚本的 {@code $input.map(...)} 恒为空数组，页面上看流程「跑成功了但什么都没发生」。
 * 现在触发器输出一行含触发类型、流程 ID 与时间戳的事件数据，
 * 与 n8n 的 trigger item 语义一致，下游节点可据此展开真实业务数据。</p>
 */
@Component
public class PassThroughExecutor implements NodeExecutor {

    @Override
    public Set<String> supports() {
        return Set.of("trigger:manual", "trigger:schedule");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trigger");
        event.put("trigger", context.node().subtype());
        event.put("flowId", context.flowId());
        event.put("ts", LocalDateTime.now().withNano(0).toString());
        return new ExecResult(List.of(event), null);
    }
}
