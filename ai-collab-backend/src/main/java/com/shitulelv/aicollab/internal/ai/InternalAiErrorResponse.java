package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** 内部端点错误信封：{@code {code, message, retryable, details?}}，不包 ApiResponse。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternalAiErrorResponse(String code, String message, boolean retryable,
        Map<String, Object> details) {

    public static InternalAiErrorResponse of(InternalAiErrorCode error) {
        return new InternalAiErrorResponse(error.name(), error.message(), error.retryable(), null);
    }

    public static InternalAiErrorResponse of(InternalAiErrorCode error, String message) {
        return new InternalAiErrorResponse(error.name(), message, error.retryable(), null);
    }
}
