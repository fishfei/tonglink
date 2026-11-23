/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.app;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>业务异常转换为 {@code Result.fail}，HTTP 状态保持 200，前端拦截器统一按 code 处理；
 * 静态资源不存在等「框架级 404」保持真实 404，避免前端把资源缺失误判为业务成功。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：直接透出 code 与中文原因。 */
    @ExceptionHandler(ApiException.class)
    public Result<Void> handleBusiness(ApiException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败：拼出第一个字段错误，便于前端直接提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldError() == null
                ? e.getMessage()
                : e.getBindingResult().getFieldError().getField() + " "
                + e.getBindingResult().getFieldError().getDefaultMessage();
        return Result.fail(Codes.VALIDATION, "参数校验失败：" + detail);
    }

    /** 静态资源找不到：保持 404，不包装成业务成功。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.fail(Codes.NOT_FOUND, "资源不存在：" + e.getResourcePath());
    }

    /** 兜底：记录完整堆栈，返回可读摘要，避免把内部细节全部暴露给前端。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Result.fail(Codes.INTERNAL, "服务异常：" + message);
    }
}
