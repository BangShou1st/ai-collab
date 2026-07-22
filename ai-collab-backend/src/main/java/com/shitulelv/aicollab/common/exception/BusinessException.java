package com.shitulelv.aicollab.common.exception;

/**
 * 表示已经预期并可安全展示给客户端的业务失败。
 * Service 抛出该异常，统一异常处理器再转换为响应；这样业务层无需依赖 Servlet API。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
