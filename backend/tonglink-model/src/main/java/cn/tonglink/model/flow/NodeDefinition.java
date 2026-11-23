/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.flow;

import java.util.Map;

/**
 * 节点定义：节点库与属性面板的元数据。
 *
 * <p>由注册中心加载后原样下发前端，前端据此渲染节点库分组与 Schema 驱动表单。
 * 内置节点来自 {@code node-definitions.json}，扩展节点来自安装的节点包。</p>
 *
 * @param type     节点大类
 * @param subtype  节点子类型
 * @param title    展示名
 * @param icon     图标标识
 * @param color    主题色
 * @param desc     一句话说明
 * @param author   作者
 * @param version  版本
 * @param builtIn  是否内置（前端用来区分「卸载」按钮是否可点）
 * @param schema   配置 Schema：{@code {title, note, fields[]}}
 */
public record NodeDefinition(String type, String subtype, String title, String icon, String color,
                             String desc, String author, String version, boolean builtIn,
                             Map<String, Object> schema) {
}
