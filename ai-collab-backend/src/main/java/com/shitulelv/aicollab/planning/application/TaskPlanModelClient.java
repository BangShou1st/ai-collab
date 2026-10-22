package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogWriter;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.util.UUID;

@Component
public class TaskPlanModelClient {
    private static final Logger log = LoggerFactory.getLogger(TaskPlanModelClient.class);

    private final ChatModelGateway gateway;
    private final PlanningModelProperties properties;
    private final AiCallLogWriter logs;
    private com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers;
    @Autowired private PlanningModelConfigurationStore configurationStore;
    private final ThreadLocal<PlanningModelConfigurationStore.Snapshot> snapshot = new ThreadLocal<>();
    @Autowired void configureProviders(com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers) { this.providers = providers; }
    public interface ConfigurationScope extends AutoCloseable { @Override void close(); }
    public ConfigurationScope openSnapshot() { snapshot.remove(); return snapshot::remove; }

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
        return generate(system, user, feature, actor, projectId, attemptId, null);
    }

    /**
     * @param correlationId stable provider correlation for the whole planning generation
     *                      (skeleton/repair/detail share it); attemptId stays the DB/logging key.
     */
    public GenerationResult generate(String system, String user, String feature,
                                     UUID actor, UUID projectId, UUID attemptId, UUID correlationId) {
        return generate(system, user, feature, actor, projectId, attemptId, correlationId, null);
    }

    /**
     * 兼容入口：{@code outputSchema} 不再参与出站决策。
     *
     * <p>规划已统一使用提示词 JSON（见 {@link #command}），schema 由提示词的
     * {@code <JSON_SCHEMA>} 段承载，因此这里保留该参数只为兼容既有调用签名，
     * 其值不会转换成任何上游的原生 JSON 模式。</p>
     */
    public GenerationResult generate(String system, String user, String feature,
                                     UUID actor, UUID projectId, UUID attemptId, UUID correlationId,
                                     JsonNode outputSchema) {
        long started = System.nanoTime();
        String correlation = correlationId != null ? correlationId.toString()
                : (attemptId != null ? attemptId.toString() : java.util.UUID.randomUUID().toString());
        com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider selected = null;
        try {
            var metadata = new com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata(correlation);
            ChatCompletionResult result;
            if (providers != null && gateway instanceof com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway routing) {
                var pinned = snapshot.get();
                if (pinned == null) {
                    if (configurationStore == null) {
                        var provider = providers.resolve(actor, ModelPurpose.PLANNING);
                        Integer outputBudget = properties.maxOutputTokens() == null
                                ? provider.maxOutputTokens() : properties.maxOutputTokens();
                        pinned = new PlanningModelConfigurationStore.Snapshot(provider, outputBudget);
                    } else pinned = configurationStore.requireSnapshot(actor, UUID.fromString(correlation),
                            properties.maxOutputTokens());
                    snapshot.set(pinned);
                }
                selected = pinned.provider();
                log.info("Planning call stage={} configurationId={} provider={} model={} mode=PROMPT_JSON outputBudget={} budgetEnforced={}",
                        feature, selected.id(), selected.providerType(), selected.modelName(),
                        pinned.maxOutputTokens(),
                        !"OPENCODE_ZEN_FREE".equals(selected.presetCode()));
                ChatCompletionCommand command = command(projectId, system, user, actor);
                result = routing.completeWithSnapshot(command, metadata, selected, pinned.maxOutputTokens());
            } else {
                ChatCompletionCommand command = new ChatCompletionCommand(
                        projectId, system, user, ChatCompletionCommand.OutputFormat.PROMPT_JSON,
                        ModelPurpose.PLANNING, null, java.util.List.of(), actor);
                result = gateway.complete(command, metadata);
            }
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
            var responseFailure = failure instanceof com.shitulelv.aicollab.infrastructure.ai.model.ProviderResponseFailure response ? response : null;
            safeLog(feature, actor, projectId, attemptId, selected == null ? "unresolved" : selected.providerType().name(),
                    selected == null ? "unresolved" : selected.modelName(),
                    status(mapped), latency, responseFailure == null ? null : responseFailure.promptTokens(),
                    responseFailure == null ? null : responseFailure.completionTokens(), mapped.name());
            throw responseFailure == null ? new BusinessException(mapped)
                    : new BusinessException(mapped, responseFailure.getMessage());
        }
    }

    private static ChatCompletionCommand command(UUID projectId, String system, String user, UUID actor) {
        // 规划统一走提示词强制 JSON（PROMPT_JSON），不向任何上游发送原生 json_schema。
        //
        // 原因：原生 json_schema 要求上游实现完整 JSON Schema 语法子集，而免费聚合网关上的
        // 模型并不都支持。实测（Kilo 免费目录 13 个模型）两类路径的合规率：
        // 原生 json_schema 6/13，提示词 JSON 8/13。原生路径更差，且会在网关直接拒绝请求
        // （MiniMax 拒绝 union 类型 "type":["string","null"]；部分模型静默忽略 schema 并返回
        // 不合规 JSON），导致规划整体不可用。
        //
        // 提示词路径的输出契约由应用侧保证：schema 随提示词下发（见 skeletonPrompt/detailPrompt
        // 的 <JSON_SCHEMA> 段），并由 TaskPlanOutputParser 做严格解析与业务校验。
        // 这也是 Zen 预设一直采用的同一策略（见 ZenModelExecution.forceJson 及其契约测试）。
        return new ChatCompletionCommand(projectId, system, user,
                ChatCompletionCommand.OutputFormat.PROMPT_JSON,
                ModelPurpose.PLANNING, null, java.util.List.of(), actor);
    }

    private void safeLog(String feature, UUID actor, UUID projectId, UUID attemptId,
                         String provider, String model, String status, long latency,
                         Integer promptTokens, Integer completionTokens, String errorCode) {
        try {
            logs.insert(UUID.randomUUID(), actor, projectId, feature, provider, model, status,
                    latency, promptTokens, completionTokens, errorCode, attemptId);
        } catch (RuntimeException failure) {
            // 调用日志写库失败不应影响规划主流程，但必须留下可追溯的记录
            log.warn("Failed to persist AI call log: feature={}, attemptId={}, status={}, errorCode={}",
                    feature, attemptId, status, errorCode, failure);
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
