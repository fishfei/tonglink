/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

import java.util.UUID;

/**
 * 统一响应包装。
 *
 * <p>前端拦截器以 {@code code} 判定业务成败：0 表示成功，其余为业务错误码。
 * HTTP 状态码保持 200，便于前端统一解包；鉴权失败仍返回 401 以便拦截器清理登录态。</p>
 *
 * @param code    业务码，0 为成功
 * @param message 提示信息（失败时为可读的中文原因）
 * @param data    业务数据，失败时为 null
 * @param traceId 链路追踪 ID，便于在日志中定位同一次请求
 */
public record Result<T>(int code, String message, T data, String traceId) {

    /** 构造成功响应。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data, newTraceId());
    }

    /** 构造无数据的成功响应。 */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 构造失败响应，code 使用 {@link Codes} 中的语义化常量。 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, newTraceId());
    }

    /** 生成不带连字符的追踪 ID，直接用于日志检索。 */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
