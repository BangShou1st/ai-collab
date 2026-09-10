package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 2.0 的模型轮次路由器。
 * 根据调用者 userId + purpose 解析个人模型配置，projectId 仅为业务上下文。
 */
@Component
public class RoutingModelTurnGateway implements ModelTurnGateway {
    private final ModelConfigurationRepository configurations;
    private final UserAiProviderService userProviders;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelTurnProviderAdapter> adapters;
    private final ZenModelExecution zen;

    @org.springframework.beans.factory.annotation.Autowired
    public RoutingModelTurnGateway(
            ModelConfigurationRepository configurations,
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters,
            ZenModelExecution zen) {
        this.configurations = configurations;
        this.userProviders = userProviders;
        this.secrets = secrets;
        EnumMap<ModelProviderType, ModelTurnProviderAdapter> indexed = new EnumMap<>(ModelProviderType.class);
        for (ModelTurnProviderAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.providerType(), adapter) != null) throw new IllegalArgumentException("重复 Model Turn Adapter");
        }
        this.adapters = Map.copyOf(indexed);
        this.zen = zen;
    }

    public RoutingModelTurnGateway(
            ModelConfigurationRepository configurations,
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters) {
        this(configurations, userProviders, secrets, adapters,
                new ZenModelExecution(new ProviderPresetRegistry(), new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.shitulelv.aicollab.common.security.OutboundEndpointPolicy()));
    }

    @Override
    public ModelTurnResult turn(ModelTurnCommand command) {
        return turn(command, AiRequestMetadata.fresh());
    }

    public ModelTurnResult turn(ModelTurnCommand command, AiRequestMetadata metadata) {
        if (command.callerUserId() != null) {
            UserAiProvider provider = userProviders.resolve(command.callerUserId(), command.purpose());
            if (zen.isZen(provider)) {
                if (!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                return zen.turn(provider, secrets.decrypt(provider.encryptedApiKey()), command, metadata);
            }
            ModelConfiguration config = provider.toModelConfiguration();
            return turnWithConfig(config, command);
        } else if (command.configurationId() != null) {
            ModelConfiguration config = configurations.findById(command.configurationId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "指定的模型配置不存在"));
            return turnWithConfig(config, command);
        } else {
            if (command.projectId() == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "未指定项目，无法查找模型配置");
            }
            ModelConfiguration config = configurations.findAssigned(command.projectId(), command.purpose())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "项目未配置 " + command.purpose() + " 模型"));
            return turnWithConfig(config, command);
        }
    }

    private ModelTurnResult turnWithConfig(ModelConfiguration config, ModelTurnCommand command) {
        if (!config.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        if (!config.capabilities().contains(ModelCapability.NATIVE_TOOLS))
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 模型必须支持 NATIVE_TOOLS");
        ModelTurnProviderAdapter adapter = adapters.get(config.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        return adapter.turn(config, secrets.decrypt(config.encryptedApiKey()), command);
    }
}
