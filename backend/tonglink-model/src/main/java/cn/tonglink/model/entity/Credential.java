/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 凭据：加密保存的敏感值（Token、数据库密码、证书等）。
 *
 * <p>{@code valueCipher} 永远是 AES-GCM 密文，接口只回传 {@code mask}，
 * 即使数据库泄露也不会直接暴露明文。</p>
 *
 * @param id          凭据 ID
 * @param name        凭据名称，节点配置中以此展示
 * @param type        凭据类型，如 API Key、数据库密码
 * @param scope       作用域，如 prod / test
 * @param valueCipher 密文
 * @param mask        掩码后的展示值
 * @param description 说明
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record Credential(String id, String name, String type, String scope, String valueCipher,
                         String mask, String description, String createdAt, String updatedAt) {
}
