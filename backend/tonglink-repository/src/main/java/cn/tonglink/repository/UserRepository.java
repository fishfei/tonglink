/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.common.IdUtil;
import cn.tonglink.model.entity.UserAccount;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用户账号持久化。
 *
 * <p>登录链路只需要按用户名查询，因此不提供分页与复杂条件查询。</p>
 */
@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> USER = (rs, i) -> new UserAccount(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("display_name"),
            rs.getInt("enabled") == 1);

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按登录名查询，不存在返回 null。 */
    public UserAccount findByUsername(String username) {
        List<UserAccount> rows = jdbc.query("SELECT * FROM sys_user WHERE username = ?", USER, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 全部用户，按用户名排序。 */
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT * FROM sys_user ORDER BY username", USER);
    }

    /** 新增用户，创建时间由本方法生成，避免调用方重复拼时间。 */
    public void insert(UserAccount user) {
        jdbc.update("INSERT INTO sys_user(id,username,password_hash,role,display_name,enabled,created_at) VALUES(?,?,?,?,?,?,?)",
                user.id(), user.username(), user.passwordHash(), user.role(), user.displayName(),
                user.enabled() ? 1 : 0, LocalDateTime.now().toString());
    }

    /** 清空所有账号，供开发/测试环境重建内置账号使用。 */
    public void clear() {
        jdbc.update("DELETE FROM sys_user");
    }

    /** 删除指定账号；系统只保留 admin 时用于移除旧的内置演示角色。 */
    public void deleteByUsername(String username) {
        jdbc.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    /** 用户总数，用于判断是否需要写入内置账号。 */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        return n == null ? 0L : n;
    }

    /** 生成用户 ID。 */
    public String newId() {
        return IdUtil.prefix("U");
    }
}
