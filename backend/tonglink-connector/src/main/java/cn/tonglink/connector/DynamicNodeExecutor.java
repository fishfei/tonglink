/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.model.flow.GraphNode;

/**
 * 可动态扩展的节点执行器。
 *
 * <p>普通执行器在启动时用静态 {@code supports()} 注册；插件节点是运行时安装的，
 * 因此由注册表在每次取执行器时调用 {@link #supports(GraphNode)} 动态判断。</p>
 */
public interface DynamicNodeExecutor extends NodeExecutor {

    /** 该执行器能否执行这个节点（依据已安装的节点包运行时配置）。 */
    boolean supports(GraphNode node);
}
