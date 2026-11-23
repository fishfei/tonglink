/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 流程版本快照：发布时冻结的图结构。
 *
 * <p>不可变记录，用于「谁在什么时候发布了什么」。回滚即把旧版本重新发布一次。</p>
 *
 * @param id        版本记录 ID
 * @param flowId    所属流程
 * @param version   版本号，从 1 递增
 * @param graphJson 该版本的流程图 JSON
 * @param changelog 变更说明
 * @param createdAt 发布时间
 */
public record FlowVersion(String id, String flowId, int version, String graphJson,
                          String changelog, String createdAt) {
}
