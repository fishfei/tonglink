/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 数据源：连接参数与认证凭据的统一配置载体。
 *
 * <p>连接信息（endpoint / jdbcUrl / topic 等）放 {@code configJson}，按 {@code protocol}
 * 渲染表单；认证信息（Token / 密码 / 证书等）放 {@code credentialJson}，按
 * {@code credentialType} 渲染表单。节点配置中的凭据字段和连接字段都引用数据源 ID，
 * 因此不再有独立的「凭据管理」概念。</p>
 *
 * @param id             数据源 ID，形如 {@code DS-xxx}
 * @param name           数据源名称
 * @param category       类别：OT（工业现场）、IT（业务系统）或 CREDENTIAL（纯认证配置）
 * @param protocol       协议名，如 {@code OPC UA}、{@code MySQL}
 * @param configJson     协议连接参数的 JSON 文本
 * @param credentialType 凭据类型键，如 {@code api_key} / {@code feishu}；空串表示无凭据
 * @param credentialJson 凭据字段的 JSON 文本（密码字段为 AES-GCM 密文）
 * @param status         状态：connected / error
 * @param lastTest       最近一次连通性测试时间
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record DataSource(String id, String name, String category, String protocol, String configJson,
                         String credentialType, String credentialJson, String status, String lastTest,
                         String createdAt, String updatedAt) {
}
