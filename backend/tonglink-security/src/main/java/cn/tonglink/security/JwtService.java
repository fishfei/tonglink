/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验。
 *
 * <p>无状态方案：服务端不存会话，令牌自带用户名与角色。
 * 密钥必须通过 {@code TONGLINK_JWT_SECRET} 覆盖，默认值仅用于本地开发。</p>
 */
@Service
public class JwtService {

    private final byte[] secret;
    private final long ttlSeconds;

    /**
     * @param secret     签名密钥，长度需满足 HS256 要求（≥32 字节）
     * @param ttlSeconds 有效期（秒），默认一天
     */
    public JwtService(@Value("${tonglink.jwt.secret:tonglink-studio-local-jwt-secret-must-be-long-enough}") String secret,
                      @Value("${tonglink.jwt.ttl-seconds:86400}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 签发访问令牌。
     *
     * @param username 用户名，作为 subject
     * @param role     角色，写入自定义 claim
     */
    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key())
                .compact();
    }

    /**
     * 解析并校验令牌，失败时抛出 jjwt 异常由过滤器吞掉。
     *
     * @return 载荷，含 subject 与 role
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret);
    }
}
