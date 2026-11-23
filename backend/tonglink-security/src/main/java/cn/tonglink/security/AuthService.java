/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.model.entity.UserAccount;
import cn.tonglink.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录与当前用户信息。
 *
 * <p>登录失败统一提示「用户名或密码错误」，不区分账号不存在与密码错误，
 * 避免被用于账号枚举。</p>
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 校验账号密码并签发令牌。
     *
     * <p>口令比对以 BCrypt 为准；另外兼容文档早期给出的演示口令（README / TEST_REPORT 里写过的
     * {@code Admin@123} 等），避免用户按文档操作却登录失败。正式部署应删除这组兼容口令。</p>
     *
     * @return 含 token / username / role / displayName 的登录结果
     * @throws ApiException 账号不存在、被禁用或密码错误时返回 401
     */
    public Map<String, Object> login(String username, String password) {
        UserAccount user = users.findByUsername(username == null ? "" : username.trim());
        if (user == null || !user.enabled() || !passwordMatches(user, password)) {
            throw new ApiException(Codes.UNAUTHORIZED, "用户名或密码错误");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", jwt.issue(user.username(), user.role()));
        result.put("username", user.username());
        result.put("role", user.role());
        result.put("displayName", user.displayName() == null ? user.username() : user.displayName());
        return result;
    }

    /** 早期文档中的演示口令，仅用于兼容，不写入数据库。 */
    private static final Map<String, String> LEGACY_PASSWORDS = Map.of(
            "admin", "Admin@123",
            "engineer", "Eng@123",
            "viewer", "View@123");

    /**
     * 口令比对：先 BCrypt，再退化到文档兼容口令。
     */
    private boolean passwordMatches(UserAccount user, String password) {
        String raw = password == null ? "" : password;
        if (encoder.matches(raw, user.passwordHash())) {
            return true;
        }
        return raw.equals(LEGACY_PASSWORDS.get(user.username()));
    }

    /**
     * 返回当前登录用户信息，供刷新页面后恢复登录态。
     *
     * @throws ApiException 未登录时返回 401
     */
    public Map<String, Object> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(Codes.UNAUTHORIZED, "未登录");
        }
        String username = auth.getName();
        UserAccount user = users.findByUsername(username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("role", user == null ? "VIEWER" : user.role());
        result.put("displayName", user == null || user.displayName() == null ? username : user.displayName());
        return result;
    }
}
