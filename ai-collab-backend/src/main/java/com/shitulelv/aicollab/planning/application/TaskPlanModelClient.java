package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelProperties;
import com.shitulelv.aicollab.infrastructure.ai.OpenAiCompatibleChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogWriter;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.springframework.stereotype.Component;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.time.Duration;
import java.util.UUID;

@Component
public class TaskPlanModelClient {
    private final ChatModelGateway gateway;
    private final PlanningModelProperties properties;
    private final ChatModelProperties chatProperties;
    private final AiCallLogWriter logs;
    public TaskPlanModelClient(PlanningModelProperties properties, ChatModelProperties chatProperties, AiCallLogWriter logs) {
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.logs = logs;
        this.gateway = new OpenAiCompatibleChatModelGateway(new ChatModelProperties(
                resolveEnabled(), resolveProvider(), resolveBaseUrl(), resolvePath(),
                resolveApiKey(), resolveModel(), resolveConnectTimeout(), resolveReadTimeout(),
                resolveTemperature(), resolveMaxOutputTokens()), false);
    }

    /**
     * H7: Explicit PLANNING_ENABLED=false → disabled.
     * Explicit PLANNING_ENABLED=true → enabled.
     * Missing (null) → fallback to CHAT_ENABLED.
     */
    private boolean resolveEnabled() {
        if (properties.enabled() != null) return properties.enabled();
        return chatProperties.enabled();
    }
    private String resolveProvider() { return nonBlank(properties.provider()) ? properties.provider() : chatProperties.provider(); }
    private String resolveBaseUrl() { return nonBlank(properties.baseUrl()) ? properties.baseUrl() : chatProperties.baseUrl(); }
    private String resolvePath() { return nonBlank(properties.path()) ? properties.path() : chatProperties.path(); }
    private String resolveApiKey() { return nonBlank(properties.apiKey()) ? properties.apiKey() : chatProperties.apiKey(); }
    private String resolveModel() { return nonBlank(properties.model()) ? properties.model() : chatProperties.model(); }
    private Duration resolveConnectTimeout() { return properties.connectTimeout() != null ? properties.connectTimeout() : chatProperties.connectTimeout(); }
    private Duration resolveReadTimeout() { return properties.readTimeout() != null ? properties.readTimeout() : chatProperties.readTimeout(); }
    /** H7: temperature 0.0 is valid; only fallback when null. */
    private double resolveTemperature() { return properties.temperature() != null ? properties.temperature() : chatProperties.temperature(); }
    /** H7: maxOutputTokens 0 or negative means not set; fallback to chat. */
    private int resolveMaxOutputTokens() { return properties.maxOutputTokens() != null && properties.maxOutputTokens() > 0 ? properties.maxOutputTokens() : chatProperties.maxOutputTokens(); }
    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * P2-1: Returns GenerationResult with content + metrics.
     * Metrics are also logged to AiCallLogWriter for observability.
     */
    public GenerationResult generate(String system, String user, String feature,
                                     UUID actor, UUID projectId, UUID attemptId) {
        long started = System.nanoTime();
        try {
            ChatCompletionResult result = gateway.complete(new ChatCompletionCommand(system, user));
            safeLog(feature, actor, projectId, attemptId, result.provider(), result.model(),
                    "SUCCESS", result.latencyMs(), result.promptTokens(), result.completionTokens(), null);
            return new GenerationResult(result.content(), result.provider(), result.model(),
                    result.latencyMs(), result.promptTokens(), result.completionTokens());
        } catch (BusinessException failure) {
            ErrorCode mapped = switch (failure.getErrorCode()) {
                case AI_PROVIDER_UNAVAILABLE -> ErrorCode.PLANNING_MODEL_UNAVAILABLE;
                case AI_MODEL_TIMEOUT -> ErrorCode.PLANNING_MODEL_TIMEOUT;
                case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_MODEL_RATE_LIMITED;
                case AI_PROVIDER_INVALID_RESPONSE -> ErrorCode.PLANNING_MODEL_INVALID_OUTPUT;
                default -> failure.getErrorCode();
            };
            long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            safeLog(feature, actor, projectId, attemptId, safe(properties.provider()), safe(properties.model()),
                    status(mapped), latency, null, null, mapped.name());
            throw new BusinessException(mapped);
        }
    }

    private void safeLog(String feature, UUID actor, UUID projectId, UUID attemptId,
                         String provider, String model, String status, long latency,
                         Integer promptTokens, Integer completionTokens, String errorCode) {
        try {
            logs.insert(UUID.randomUUID(), actor, projectId, feature, provider, model, status,
                    latency, promptTokens, completionTokens, errorCode, attemptId);
        } catch (RuntimeException ignored) {
        }
    }

    private static String status(ErrorCode code) {
        return switch (code) {
            case PLANNING_MODEL_TIMEOUT -> "TIMEOUT";
            case PLANNING_MODEL_RATE_LIMITED -> "QUOTA_EXCEEDED";
            case PLANNING_MODEL_INVALID_OUTPUT -> "INVALID_OUTPUT";
            default -> "PROVIDER_ERROR";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unconfigured" : value.strip();
    }
}
