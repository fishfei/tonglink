/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 已安装的节点包记录。
 *
 * <p>同一 {@code packageId} 可存在多条记录以支持多版本并存；
 * 注册中心启动时只加载状态为 installed 的记录。</p>
 *
 * @param id           记录 ID
 * @param packageId    节点包标识
 * @param version      版本号
 * @param manifestJson 节点包清单（含 nodes 数组与 schema）
 * @param status       状态：installed / disabled
 * @param installedAt  安装时间
 */
public record NodePackage(String id, String packageId, String version, String manifestJson,
                          String status, String installedAt) {
}
