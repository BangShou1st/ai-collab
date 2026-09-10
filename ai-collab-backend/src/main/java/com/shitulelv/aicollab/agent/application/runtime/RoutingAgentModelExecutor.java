package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 模型执行路由。根据当前 Agent 模型的能力选择执行路径：
 * - NATIVE_TOOLS -> NativeToolCallingExecutor（失败时自动降级到 Legacy）
 * - CHAT-only -> LegacyReadOnlyAgentExecutor
 * - 两者都不支持 -> 明确失败
 */
@Component
public class RoutingAgentModelExecutor {
    private static final Logger log = LoggerFactory.getLogger(RoutingAgentModelExecutor.class);

    private final UserAiProviderService userProviders;
    private final NativeToolCallingExecutor nativeExecutor;
    private final LegacyReadOnlyAgentExecutor legacyExecutor;

    public RoutingAgentModelExecutor(
            UserAiProviderService userProviders,
            NativeToolCallingExecutor nativeExecutor,
            LegacyReadOnlyAgentExecutor legacyExecutor) {
        this.userProviders = userProviders;
        this.nativeExecutor = nativeExecutor;
        this.legacyExecutor = legacyExecutor;
    }

    /**
     * 调用模型并返回结果。
     * 如果模型声明支持 NATIVE_TOOLS 但实际调用失败，自动降级到 Legacy 模式。
     *
     * @param run              当前运行
     * @param messages         多轮消息历史
     * @param exposed          当前暴露的工具定义
     * @param correctionAttempted 是否已尝试过参数修正（Legacy 模式用）
     * @return 模型返回结果
     */
    public ModelTurnResult callModel(
            AgentRunView run,
            List<ModelMessage> messages,
            List<AgentToolDefinition> exposed,
            boolean correctionAttempted) {

        UserAiProvider provider = userProviders.resolve(run.requesterId(), ModelPurpose.AGENT);
        ModelConfiguration config = provider.toModelConfiguration();

        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "Agent 模型配置已禁用");
        }

        boolean hasNativeTools = config.capabilities().contains(ModelCapability.NATIVE_TOOLS);
        boolean hasChat = config.capabilities().contains(ModelCapability.CHAT);

        if (hasNativeTools) {
            log.debug("使用 Native Tool Calling 执行器: model={}, configurationId={}",
                    config.modelName(), config.id());
            try {
                return nativeExecutor.callModel(messages, exposed, run.projectId(), run.requesterId(), run.sessionId());
            } catch (BusinessException e) {
                // 如果是模型调用错误且支持 CHAT，降级到 Legacy 模式
                if (hasChat && isNativeToolError(e)) {
                    log.warn("Native Tool Calling 失败，降级到 Legacy 模式: model={}, error={}",
                            config.modelName(), e.getErrorCode());
                    return fallbackToLegacy(messages, exposed, run.projectId(), run.requesterId(), run.sessionId(),
                            correctionAttempted);
                }
                throw e;
            }
        }

        if (hasChat) {
            log.debug("使用 Legacy 只读执行器: model={}, configurationId={}",
                    config.modelName(), config.id());
            return fallbackToLegacy(messages, exposed, run.projectId(), run.requesterId(), run.sessionId(),
                    correctionAttempted);
        }

        // 既不支持 NATIVE_TOOLS 也不支持 CHAT -> 明确失败
        throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                "当前 Agent 模型既不支持 NATIVE_TOOLS 也不支持 CHAT");
    }

    /**
     * 降级到 Legacy 执行器。
     * Legacy 模式下只暴露只读工具。
     */
    private ModelTurnResult fallbackToLegacy(
            List<ModelMessage> messages,
            List<AgentToolDefinition> exposed,
            UUID projectId,
            UUID callerUserId,
            UUID sessionId,
            boolean correctionAttempted) {
        List<AgentToolDefinition> readOnlyExposed = exposed.stream()
                .filter(d -> !d.writesBusinessData())
                .toList();
        return legacyExecutor.callModel(messages, readOnlyExposed, projectId, callerUserId,
                correctionAttempted, sessionId);
    }

    /**
     * 判断异常是否为 Native Tool Calling 相关错误（可降级）。
     */
    private boolean isNativeToolError(BusinessException e) {
        return e.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR
                || e.getErrorCode() == ErrorCode.AI_PROVIDER_INVALID_RESPONSE
                || e.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT;
    }

    /**
     * 判断当前模型是否为 Legacy 模式（只支持 CHAT）。
     */
    public boolean isLegacyMode(UUID callerUserId) {
        UserAiProvider provider;
        try {
            provider = userProviders.resolve(callerUserId, ModelPurpose.AGENT);
        } catch (BusinessException exception) {
            return false;
        }
        return !provider.toModelConfiguration().capabilities().contains(ModelCapability.NATIVE_TOOLS)
                && provider.toModelConfiguration().capabilities().contains(ModelCapability.CHAT);
    }
}
