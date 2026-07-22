package com.shitulelv.aicollab.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 集中定义客户端可依赖的业务错误码、默认提示与 HTTP 语义。
 * 它连接业务异常和 HTTP 层，避免各处自行选择状态码造成契约不一致。
 */
public enum ErrorCode {
    SUCCESS(HttpStatus.OK, "操作成功"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "当前登录状态无效，请重新登录"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行此操作"),
    USER_DISABLED(HttpStatus.FORBIDDEN, "账号已被禁用"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
