/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

/**
 * 业务错误码常量。
 *
 * <p>与 HTTP 状态码语义保持一致，减少前后端沟通成本；但响应体始终是 HTTP 200 + {@code Result.code}，
 * 唯一例外是鉴权失败由 Spring Security 直接返回 401。</p>
 */
public final class Codes {

    /** 请求参数不合法。 */
    public static final int BAD_REQUEST = 400;
    /** 未登录或令牌失效。 */
    public static final int UNAUTHORIZED = 401;
    /** 已登录但无权限。 */
    public static final int FORBIDDEN = 403;
    /** 资源不存在。 */
    public static final int NOT_FOUND = 404;
    /** 状态冲突，例如项目下仍有流程时禁止删除。 */
    public static final int CONFLICT = 409;
    /** 必填字段缺失等校验失败。 */
    public static final int VALIDATION = 422;
    /** 未预期的服务端异常。 */
    public static final int INTERNAL = 500;

    private Codes() {
    }
}
