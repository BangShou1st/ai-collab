package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 模型执行路由。根据当前 Agent 模型的能力选择执行路径：
 * - NATIVE_TOOLS -> NativeToolCallingExecutor（协议失败保持原始错误）
 * - CHAT-only -> LegacyReadOnlyAgentExecutor
 * - 两者都不支持 -> 明确失败
 */
@Component
public class RoutingAgentModelExecutor {
    private static final Logger log = LoggerFactory.getLogger(RoutingAgentModelExecutor.class);

    private final NativeToolCallingExecutor nativeExecutor;
    private final LegacyReadOnlyAgentExecutor legacyExecutor;
    private final ZenModelExecution zen;
    private final AgentModelConfigurationStore configurationStore;

    public RoutingAgentModelExecutor(
            NativeToolCallingExecutor nativeExecutor,
            LegacyReadOnlyAgentExecutor legacyExecutor,
            ZenModelExecution zen,
            AgentModelConfigurationStore configurationStore) {
        this.nativeExecutor = nativeExecutor;
        this.legacyExecutor = legacyExecutor;
        this.zen = zen;
        this.configurationStore = configurationStore;
    }

    /**
     * 单次模型请求的准备结果：请求准备时解析一次当前 AGENT 配置，
     * 同一份配置用于窗口预算、能力判断、工具协议、出站调用及该响应的工具校验。
     * 下一次请求重新调用 resolveRequest 读取最新配置。
     */
    public record ResolvedRequest(UserAiProvider provider, ModelConfiguration config,
            String providerType, String modelName, boolean legacyMode) {

        /** 由已解析的配置派生请求快照：模式按能力判定，调用方不得另行重读配置。 */
        public static ResolvedRequest of(UserAiProvider provider, ModelConfiguration config) {
            boolean legacyMode = !config.capabilities().contains(ModelCapability.NATIVE_TOOLS)
                    && config.capabilities().contains(ModelCapability.CHAT);
            return new ResolvedRequest(provider, config, provider.providerType().name(),
                    provider.modelName(), legacyMode);
        }
    }

    /** 解析当前 AGENT 用途配置；配置不可用时沿现有错误分类失败。 */
    public ResolvedRequest resolveRequest(AgentRunView run) {
        UserAiProvider provider = configurationStore.require(run);
        ModelConfiguration config = zen.isZen(provider) ? zen.runtimeConfig(provider) : provider.toModelConfiguration();
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 模型配置已禁用");
        }
        return ResolvedRequest.of(provider, config);
    }

    /**
     * 使用请求准备时解析的同一份配置出站；不再重新读取配置，避免准备按 A、调用按 B。
     *
     * @param run                当前运行
     * @param messages           多轮消息历史
     * @param exposed            当前暴露的工具定义
     * @param correctionAttempted 是否已尝试过参数修正（Legacy 模式用）
     * @param resolved           本次请求准备阶段解析出的配置快照（唯一配置来源）
     */
    public ModelTurnResult callModel(
            AgentRunView run,
            List<ModelMessage> messages,
            List<AgentToolDefinition> exposed,
            boolean correctionAttempted,
            ResolvedRequest resolved) {

        ModelConfiguration config = resolved.config();
        boolean hasNativeTools = config.capabilities().contains(ModelCapability.NATIVE_TOOLS);
        boolean hasChat = config.capabilities().contains(ModelCapability.CHAT);

        if (hasNativeTools) {
            log.debug("使用 Native Tool Calling 执行器: model={}, configurationId={}",
                    config.modelName(), config.id());
            try (var scope = new com.shitulelv.aicollab.infrastructure.ai.model.AiConfigurationContext(resolved.provider())) {
                return nativeExecutor.callModel(messages, exposed, run.projectId(), run.requesterId(), run.sessionId());
            }
        }

        if (hasChat) {
            log.debug("使用 Legacy 只读执行器: model={}, configurationId={}",
                    config.modelName(), config.id());
            try (var scope = new com.shitulelv.aicollab.infrastructure.ai.model.AiConfigurationContext(resolved.provider())) {
                return fallbackToLegacy(messages, exposed, run.projectId(), run.requesterId(), run.sessionId(), correctionAttempted);
            }
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
     * 判断当前模型是否为 Legacy 模式（只支持 CHAT）。
     */
    public boolean isLegacyModeForRun(AgentRunView run) {
        UserAiProvider provider=configurationStore.require(run);
        ModelConfiguration config=zen.isZen(provider) ? zen.runtimeConfig(provider) : provider.toModelConfiguration();
        return !config.capabilities().contains(ModelCapability.NATIVE_TOOLS) && config.capabilities().contains(ModelCapability.CHAT);
    }

    /** 无来源记录的旧待处理调用回退：按当前配置保守校验（仅恢复路径兜底使用）。 */
    public ProviderIdentity pinnedProviderIdentity(AgentRunView run) {
        try {
            UserAiProvider provider = configurationStore.require(run);
            return new ProviderIdentity(provider.providerType().name(), provider.modelName());
        } catch (BusinessException failure) {
            return null;
        }
    }

    /**
     * 无工具的纯文本模型调用，供有界会话摘要等辅助任务使用。
     * 使用本次运行固定的配置快照；调用方负责单独记账，不得混入普通决策轮次。
     * 仅原生 Tool Calling 模型支持（Legacy 决策协议不适用于摘要任务，返回 UnsupportedOperationException 语义由调用方处理）。
     */
    public ModelTurnResult callModelWithoutTools(AgentRunView run, List<ModelMessage> messages) {
        return callModelWithoutTools(run, messages, resolveRequest(run));
    }

    /**
     * 同上，但配置快照由调用方在请求准备时解析并传入（C5）：
     * 辅助请求的窗口核对、输出封顶与出站调用使用同一份快照，
     * 避免准备按 A、调用按 B；下一次实际请求由调用方重新解析。
     */
    public ModelTurnResult callModelWithoutTools(AgentRunView run, List<ModelMessage> messages, ResolvedRequest resolved) {
        ModelConfiguration config = resolved.config();
        if (!config.capabilities().contains(ModelCapability.NATIVE_TOOLS)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "当前模型不支持原生调用，无法执行摘要任务");
        }
        try (var scope = new com.shitulelv.aicollab.infrastructure.ai.model.AiConfigurationContext(resolved.provider())) {
            return nativeExecutor.callModel(messages, List.of(), run.projectId(), run.requesterId(), run.sessionId());
        }
    }

    public record ProviderIdentity(String providerType, String modelName) {
    }
}
