/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 项目：流程的分类容器。
 *
 * @param id          项目 ID，形如 {@code P-xxx}
 * @param name        项目名称
 * @param code        项目编码，便于与外部系统对齐
 * @param color       标识色，前端用于区分列表项
 * @param description 项目用途说明
 * @param createdAt   创建时间（ISO-8601 字符串）
 * @param updatedAt   更新时间
 */
public record Project(String id, String name, String code, String color, String description,
                      String createdAt, String updatedAt) {
}
