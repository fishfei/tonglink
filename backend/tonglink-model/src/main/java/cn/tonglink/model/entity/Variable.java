/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 变量：流程执行期可引用的键值对。
 *
 * <p>与凭据的区别是语义：变量偏配置（阈值、间隔），凭据偏秘密。
 * 两者都加密存库，但变量允许在节点配置里以 {@code {{ $vars.KEY }}} 方式引用。</p>
 *
 * @param id          变量 ID
 * @param name        变量名称（可读名）
 * @param key         变量键，流程中引用的标识
 * @param env         环境，如 prod / test
 * @param valueCipher 密文
 * @param scope       作用域，如 global / project
 * @param description 说明
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record Variable(String id, String name, String key, String env, String valueCipher,
                       String scope, String description, String createdAt, String updatedAt) {
}
