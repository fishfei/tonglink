/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import cn.tonglink.common.Result;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>{@code /auth/login} 在安全配置中放行，{@code /auth/me} 需要携带令牌。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /** 登录：请求体 {@code {username, password}}。 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return Result.ok(service.login(body.get("username"), body.get("password")));
    }

    /** 当前登录用户信息。 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.ok(service.current());
    }
}
