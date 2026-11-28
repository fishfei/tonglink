/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：无状态 JWT + 白名单放行静态资源与登录接口。
 *
 * <p>前端由 Vite 代理或 Spring Boot 直接托管，均属同源访问，因此关闭 CSRF 与 CORS；
 * 若前后端分离部署到不同域名，需要在此打开 CORS 并配置白名单。</p>
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    /** 密码编码器：BCrypt 自带随机盐，同一明文每次哈希结果不同。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 内部路由转发与 SSE 异步完成分发不应被重复鉴权：
                        // ASYNC 分发是容器为结束已建立响应而做的二次分发，外部无法直接发起，
                        // 真正的鉴权已在初始 REQUEST 完成。若不放行，SseEmitter.complete()
                        // 触发的 ASYNC 分发会因 SecurityContext 为空而抛 AccessDeniedException。
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR,
                                DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/v1/auth/login", "/actuator/**", "/ws/**",
                                "/", "/index.html", "/assets/**", "/favicon.ico",
                                "/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**",
                                "/webjars/**", "/v3/api-docs/**").permitAll()
                        // 节点定义只读且不含敏感信息，允许在登录前预览节点库
                        .requestMatchers(HttpMethod.GET, "/api/v1/nodes/definitions").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(401, "未登录或登录已过期")));
        return http.build();
    }
}
