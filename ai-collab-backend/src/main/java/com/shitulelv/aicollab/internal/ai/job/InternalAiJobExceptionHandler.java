package com.shitulelv.aicollab.internal.ai.job;

import com.shitulelv.aicollab.agent.job.AiJobException;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 内部 Job 端点的框架级异常出口：只作用于 {@link InternalAiJobController}，
 * 保持扁平错误信封（不落回业务 ApiResponse）。
 *
 * <p>{@code STALE_LEASE} 固定 HTTP 409；NOT_FOUND 404；其余租约/状态/参数问题按
 * 422 INVALID_REQUEST，未预期异常 500 INTERNAL（不回显上游正文）。</p>
 */
@RestControllerAdvice(assignableTypes = InternalAiJobController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAiJobExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalAiJobExceptionHandler.class);

    @ExceptionHandler(AiJobException.class)
    public ResponseEntity<InternalAiErrorResponse> handleAiJob(AiJobException failure) {
        return switch (failure.kind()) {
            case NOT_FOUND -> ResponseEntity.status(404).body(
                    InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST, "AI Job 不存在"));
            case STALE_LEASE -> ResponseEntity.status(InternalAiErrorCode.STALE_LEASE.httpStatus())
                    .body(InternalAiErrorResponse.of(InternalAiErrorCode.STALE_LEASE));
            case INVALID_STATE, INVALID_REQUEST -> ResponseEntity
                    .status(InternalAiErrorCode.INVALID_REQUEST.httpStatus())
                    .body(InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST,
                            failure.getMessage() == null ? "AI Job 请求非法" : failure.getMessage()));
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InternalAiErrorResponse> handleUnreadableBody() {
        return ResponseEntity.status(InternalAiErrorCode.INVALID_REQUEST.httpStatus())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST,
                        "请求体不是合法 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalAiErrorResponse> handleUnexpected(Exception failure) {
        log.error("内部 Job 端点未预期异常: type={}", failure.getClass().getSimpleName());
        return ResponseEntity.status(InternalAiErrorCode.INTERNAL.httpStatus())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.INTERNAL));
    }
}
