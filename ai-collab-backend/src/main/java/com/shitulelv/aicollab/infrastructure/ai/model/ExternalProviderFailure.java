package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

/** Structured, safe classification for failures known to originate at an external model provider. */
public final class ExternalProviderFailure extends BusinessException {
    public enum Kind {
        RATE_LIMITED,
        UPSTREAM_TIMEOUT,
        UPSTREAM_5XX,
        UPSTREAM_TRANSPORT
    }

    private final Kind kind;
    private final int statusCode;

    public ExternalProviderFailure(ErrorCode errorCode, Kind kind, int statusCode) {
        super(errorCode);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }
}
