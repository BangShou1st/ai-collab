package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogWriter;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.util.UUID;

@Component
public class TaskPlanModelClient {
    private final ChatModelGateway gateway;
    private final PlanningModelProperties properties;
    private final AiCallLogWriter logs;

    @Autowired
    public TaskPlanModelClient(
            PlanningModelProperties properties,
            AiCallLogWriter logs,
            @Qualifier("routingChatModelGateway") ChatModelGateway gateway) {
        this.properties = properties;
        this.logs = logs;
        this.gateway = gateway;
    }

    /**
     * Returns GenerationResult with content + metrics.
     * Metrics are also logged to AiCallLogWriter for observability.
     */
    public GenerationResult generate(String system, String user, String feature,
                                     UUID actor, UUID projectId, UUID attemptId) {
        long started = System.nanoTime();
        try {
            ChatCompletionResult result = gateway.complete(new ChatCompletionCommand(
                    projectId, system, user,
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                    ModelPurpose.PLANNING, null, java.util.List.of(), actor),
                    new com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata(attemptId != null ? attemptId.toString() : java.util.UUID.randomUUID().toString()));
            safeLog(feature, actor, projectId, attemptId, result.provider(), result.model(),
                    "SUCCESS", result.latencyMs(), result.promptTokens(), result.completionTokens(), null);
            return new GenerationResult(result.content(), result.provider(), result.model(),
                    result.latencyMs(), result.promptTokens(), result.completionTokens());
        } catch (BusinessException failure) {
            ErrorCode mapped = switch (failure.getErrorCode()) {
                case AI_PROVIDER_UNAVAILABLE -> ErrorCode.PLANNING_MODEL_UNAVAILABLE;
                case AI_MODEL_TIMEOUT -> ErrorCode.PLANNING_MODEL_TIMEOUT;
                case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_PROVIDER_QUOTA_EXCEEDED;
                case AI_PROVIDER_INVALID_RESPONSE -> ErrorCode.PLANNING_MODEL_INVALID_OUTPUT;
                case AI_PROVIDER_OUTPUT_TRUNCATED -> ErrorCode.PLANNING_MODEL_OUTPUT_TRUNCATED;
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
            case PLANNING_PROVIDER_QUOTA_EXCEEDED -> "PROVIDER_QUOTA_EXCEEDED";
            case PLANNING_MODEL_INVALID_OUTPUT -> "INVALID_OUTPUT";
            default -> "PROVIDER_ERROR";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unconfigured" : value.strip();
    }
}
