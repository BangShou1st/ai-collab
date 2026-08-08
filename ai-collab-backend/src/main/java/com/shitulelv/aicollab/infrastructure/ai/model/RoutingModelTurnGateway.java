package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 2.0 的模型轮次路由器。
 * 根据 projectId + purpose 查找项目专属的模型配置。
 */
@Component
public class RoutingModelTurnGateway implements ModelTurnGateway {
    private final ModelConfigurationRepository configurations;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelTurnProviderAdapter> adapters;

    public RoutingModelTurnGateway(
            ModelConfigurationRepository configurations,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters) {
        this.configurations = configurations;
        this.secrets = secrets;
        EnumMap<ModelProviderType, ModelTurnProviderAdapter> indexed =
                new EnumMap<>(ModelProviderType.class);
        for (ModelTurnProviderAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.providerType(), adapter) != null) {
                throw new IllegalArgumentException("重复 Model Turn Adapter");
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    @Override
    public ModelTurnResult turn(ModelTurnCommand command) {
        ModelConfiguration config;
        if (command.configurationId() != null) {
            config = configurations.findById(command.configurationId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                            "指定的模型配置不存在"));
        } else {
            if (command.projectId() == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "未指定项目，无法查找模型配置");
            }
            config = configurations.findAssigned(command.projectId(), command.purpose())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                            "项目未配置 " + command.purpose() + " 模型"));
        }
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (!config.capabilities().contains(ModelCapability.NATIVE_TOOLS)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "Agent 模型必须支持 NATIVE_TOOLS");
        }
        ModelTurnProviderAdapter adapter = adapters.get(config.providerType());
        if (adapter == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return adapter.turn(config, secrets.decrypt(config.encryptedApiKey()), command);
    }
}
