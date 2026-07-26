package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AiCallLogService {
    private static final Logger log = LoggerFactory.getLogger(AiCallLogService.class);
    private static final String INVALID_CITATION_OUTPUT = "KNOWLEDGE_CITATION_INVALID";
    private final AiCallLogWriter writer;
    private final ChatModelProperties properties;

    public AiCallLogService(AiCallLogWriter writer, ChatModelProperties properties) {
        this.writer = writer;
        this.properties = properties;
    }

    public void success(
            UUID requestId, UUID userId, UUID projectId, ChatCompletionResult result) {
        safeInsert(requestId, userId, projectId, result.provider(), result.model(),
                "SUCCESS", result.latencyMs(), result.promptTokens(),
                result.completionTokens(), null);
    }

    public void invalidOutput(
            UUID requestId, UUID userId, UUID projectId, ChatCompletionResult result) {
        safeInsert(requestId, userId, projectId, result.provider(), result.model(),
                "INVALID_OUTPUT", result.latencyMs(), result.promptTokens(),
                result.completionTokens(), INVALID_CITATION_OUTPUT);
    }

    public void failure(
            UUID requestId, UUID userId, UUID projectId, ErrorCode errorCode, long latencyMs) {
        safeInsert(requestId, userId, projectId, safe(properties.provider()),
                safe(properties.model()), status(errorCode), latencyMs,
                null, null, errorCode.name());
    }

    private void safeInsert(
            UUID requestId, UUID userId, UUID projectId, String provider, String model,
            String status, long latencyMs, Integer promptTokens,
            Integer completionTokens, String errorCode) {
        try {
            writer.insert(UUID.randomUUID(), userId, projectId, "KNOWLEDGE_QA", provider, model,
                    status, latencyMs, promptTokens, completionTokens, errorCode, requestId);
        } catch (RuntimeException exception) {
            log.warn("AI 调用日志写入失败，requestId={}", requestId);
        }
    }

    private static String status(ErrorCode errorCode) {
        return switch (errorCode) {
            case AI_MODEL_TIMEOUT -> "TIMEOUT";
            case AI_PROVIDER_QUOTA_EXCEEDED -> "QUOTA_EXCEEDED";
            case AI_PROVIDER_INVALID_RESPONSE -> "INVALID_OUTPUT";
            default -> "PROVIDER_ERROR";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unconfigured" : value.strip();
    }
}
