package com.shitulelv.aicollab.common.api;

import com.shitulelv.aicollab.common.exception.ErrorCode;

/**
 * REST 接口的统一响应信封。
 * 它位于 HTTP 边界，保证成功与失败都具有稳定的 code、message、data 结构，
 * 使客户端不必根据每个 Controller 猜测响应格式。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), ErrorCode.SUCCESS.message(), data);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.name(), errorCode.message(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.name(), message, null);
    }
}
