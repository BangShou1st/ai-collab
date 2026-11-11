package com.shitulelv.aicollab.internal.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/**
 * 内部 AI 端点的框架级异常出口。
 *
 * <p>只作用于 {@link InternalModelCallController}，优先级高于全局
 * {@code GlobalExceptionHandler}：内部端点的响应契约是扁平错误信封，
 * <b>不能</b>落回业务 {@code ApiResponse}。</p>
 */
@RestControllerAdvice(assignableTypes = InternalModelCallController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalAiExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InternalAiErrorResponse> handleUnreadableBody() {
        return ResponseEntity.status(InternalAiErrorCode.INVALID_REQUEST.httpStatus())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST, "请求体不是合法 JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<InternalAiErrorResponse> handleUnsupportedMediaType() {
        return ResponseEntity.status(InternalAiErrorCode.INVALID_REQUEST.httpStatus())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST,
                        "请求 Content-Type 必须是 application/json"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalAiErrorResponse> handleUnexpected(Exception failure) {
        log.error("内部 AI 端点未预期异常: type={}", failure.getClass().getSimpleName());
        return ResponseEntity.status(InternalAiErrorCode.INTERNAL.httpStatus())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.INTERNAL));
    }
}
