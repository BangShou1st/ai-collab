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
    private final UserAiProviderService userProviders;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelTurnProviderAdapter> adapters;
    private final ZenModelExecution zen;
    private final KiloGatewayModelExecution kilo;

    @org.springframework.beans.factory.annotation.Autowired
    public RoutingModelTurnGateway(
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters,
            ZenModelExecution zen,
            KiloGatewayModelExecution kilo) {
        this.userProviders = userProviders;
        this.secrets = secrets;
        EnumMap<ModelProviderType, ModelTurnProviderAdapter> indexed = new EnumMap<>(ModelProviderType.class);
        for (ModelTurnProviderAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.providerType(), adapter) != null) throw new IllegalArgumentException("重复 Model Turn Adapter");
        }
        this.adapters = Map.copyOf(indexed);
        this.zen = zen;
        this.kilo = kilo;
    }

    public RoutingModelTurnGateway(
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters,
            ZenModelExecution zen) {
        this(userProviders, secrets, adapters, zen, KiloGatewayModelExecution.standalone());
    }

    public RoutingModelTurnGateway(
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters) {
        this(userProviders, secrets, adapters,
                new ZenModelExecution(new ProviderPresetRegistry(), new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.shitulelv.aicollab.common.security.OutboundEndpointPolicy()),
                KiloGatewayModelExecution.standalone());
    }

    @Override
    public ModelTurnResult turn(ModelTurnCommand command) {
        return turn(command, AiRequestMetadata.fresh());
    }

    public ModelTurnResult turn(ModelTurnCommand command, AiRequestMetadata metadata) {
        if (command.callerUserId() != null) {
            UserAiProvider provider = AiConfigurationContext.current();
            if (provider != null) {
                if (!provider.userId().equals(command.callerUserId())) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                userProviders.requireSnapshotAuthorized(provider);
            } else provider = userProviders.resolve(command.callerUserId(), command.purpose());
            if (zen.isZen(provider)) {
                if (!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                return zen.turn(provider, secrets.decryptOptional(provider.encryptedApiKey()), command, metadata);
            }
            if (kilo.isKilo(provider)) {
                if (!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                return kilo.turn(provider, command);
            }
            return turnWithConfig(provider.toModelConfiguration(), command);
        }
        throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                "未指定调用用户，无法查找模型配置");
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
