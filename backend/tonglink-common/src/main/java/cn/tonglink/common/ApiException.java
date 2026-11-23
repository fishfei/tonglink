/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

/**
 * 业务异常。
 *
 * <p>业务代码只需抛出本异常，由 {@code GlobalExceptionHandler} 统一转换为 {@link Result}。
 * 相比直接返回 ResponseEntity，异常方式能让事务回滚，也避免每层都判空。</p>
 */
public class ApiException extends RuntimeException {

    /** 业务错误码。 */
    private final int code;

    /**
     * @param code    业务错误码，取值见 {@link Codes}
     * @param message 可读的错误原因，会直接展示给用户
     */
    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 默认按参数错误（400）处理。 */
    public ApiException(String message) {
        this(Codes.BAD_REQUEST, message);
    }

    public int getCode() {
        return code;
    }
}
