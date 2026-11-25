/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.tonglink.datasource.DataSourceService;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.node.NodeRegistry;
import cn.tonglink.taskrunner.CodeRunnerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 安装节点包后必须由真实插件执行器接管，不能继续走透传/模拟兜底。 */
class NodeExecutorRegistryTest {

    @Test
    void installedPluginTakesPrecedence() {
        NodeRegistry registry = mock(NodeRegistry.class);
        when(registry.runtimeFor("output", "redis")).thenReturn(Map.of("kind", "redis"));
        PluginExecutor plugin = new PluginExecutor(registry, mock(CodeRunnerService.class),
                mock(DataSourceService.class));
        PassThroughExecutor fallback = new PassThroughExecutor();
        NodeExecutorRegistry executors = new NodeExecutorRegistry(List.of(plugin), fallback);

        GraphNode node = new GraphNode("n1", "output", "redis", "1", "Redis 写入",
                Map.of(), Map.of());
        assertSame(plugin, executors.get(node));
    }
}
