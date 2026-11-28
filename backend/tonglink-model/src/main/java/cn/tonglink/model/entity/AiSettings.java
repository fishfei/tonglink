/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * AI 设置：大模型连接配置（单行记录）。
 *
 * <p>{@code provider} 取值为 {@code local} / {@code deepseek} / {@code zhipu} /
 * {@code qwen} / {@code hunyuan} / {@code custom}；{@code local} 表示使用本地规则引擎，
 * 其余值配合 {@code endpoint} + {@code apiKeyCipher} + {@code model} 走 OpenAI 兼容接口。</p>
 *
 * <p>API Key 只以密文存库，接口回显时一律掩码，明文不会离开服务端。</p>
 *
 * @param id           固定为 {@code default}
 * @param provider     引擎标识
 * @param endpoint     Chat Completions 完整地址
 * @param apiKeyCipher 加密后的 API Key
 * @param model        模型名
 * @param updatedAt    更新时间（ISO-8601）
 */
public record AiSettings(String id, String provider, String endpoint, String apiKeyCipher,
                         String model, String updatedAt) {
}
