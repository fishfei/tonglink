/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 数据源：IT / OT 协议连接的配置载体。
 *
 * <p>不同协议的连接参数差异很大（OPC UA 是 endpoint、Modbus 是 host/port、JDBC 是 jdbcUrl），
 * 因此统一放 {@code configJson} 里，由前端按协议元数据动态渲染表单。</p>
 *
 * @param id         数据源 ID，形如 {@code DS-xxx}
 * @param name       数据源名称
 * @param category   类别：OT（工业现场）或 IT（业务系统）
 * @param protocol   协议名，如 {@code OPC UA}、{@code MySQL}
 * @param configJson 协议连接参数的 JSON 文本
 * @param status     连接状态：connected / error
 * @param lastTest   最近一次连接测试时间
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 */
public record DataSource(String id, String name, String category, String protocol, String configJson,
                         String status, String lastTest, String createdAt, String updatedAt) {
}
