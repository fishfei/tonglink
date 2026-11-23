/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 系统用户。
 *
 * <p>密码只保存 BCrypt 哈希；角色用于后续 RBAC 扩展，当前接口按登录态放行。</p>
 *
 * @param id           用户 ID
 * @param username     登录名
 * @param passwordHash BCrypt 哈希
 * @param role         角色：ADMIN / ENGINEER / VIEWER
 * @param displayName  展示名
 * @param enabled      是否启用
 */
public record UserAccount(String id, String username, String passwordHash, String role,
                          String displayName, boolean enabled) {
}
