/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.flow;

/**
 * 流程图连线。
 *
 * <p>连接点固定为「左进右出」：源节点 {@code sourceHandle=out}，目标节点 {@code targetHandle=in}。
 * 条件节点会输出 {@code true} / {@code false} 分支，执行器据此决定下游是否接收数据。</p>
 *
 * @param id           连线 ID
 * @param source       源节点 ID
 * @param target       目标节点 ID
 * @param sourceHandle 源端口，默认 out
 * @param targetHandle 目标端口，默认 in
 */
public record GraphEdge(String id, String source, String target, String sourceHandle,
                        String targetHandle) {
}
