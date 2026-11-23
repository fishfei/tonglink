/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.flow;

import java.util.Map;

/**
 * 流程图中的节点。
 *
 * <p>{@code type} + {@code subtype} 唯一确定节点能力；{@code nodeVersion} 随流程冻结，
 * 保证插件升级后老流程行为不变。{@code config} 的结构由节点定义中的
 * {@code schema.fields} 描述，因此这里用 Map 承载而不强类型化。</p>
 *
 * @param id          节点 ID
 * @param type        节点大类：trigger / source / code / logic / output
 * @param subtype     节点子类型，如 opcua、mysql、nodejs
 * @param nodeVersion 节点版本
 * @param title       画布上显示的名称
 * @param position    画布坐标 {@code {x, y}}
 * @param config      节点配置
 */
public record GraphNode(String id, String type, String subtype, String nodeVersion, String title,
                        Map<String, Object> position, Map<String, Object> config) {
}
