package com.shitulelv.aicollab.common.exception;

import com.shitulelv.aicollab.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * MVC 请求进入 Controller 后的统一异常出口。
 * 它把参数错误、业务错误和未知错误映射成稳定的 API 响应，并避免将堆栈等内部信息暴露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.error(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "请求体格式错误"));
    }

    /** P2-6: malformed UUID in path variable → stable 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR,
                "参数格式无效：" + exception.getName()));
    }

    /** P2-6: missing required header → stable 400. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_ERROR,
                "缺少必需请求头：" + exception.getHeaderName()));
    }

    /** R6: queue full → 503 without stack trace. */
    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<ApiResponse<Void>> handleQueueFull(RejectedExecutionException exception) {
        log.warn("规划生成队列已满，拒绝调度");
        return ResponseEntity.status(ErrorCode.PLANNING_QUEUE_FULL.httpStatus())
                .body(ApiResponse.error(ErrorCode.PLANNING_QUEUE_FULL));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge() {
        return ResponseEntity.status(ErrorCode.DOCUMENT_TOO_LARGE.httpStatus())
                .body(ApiResponse.error(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("未处理的系统异常，type={}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + "：" + fieldError.getDefaultMessage();
    }
}
