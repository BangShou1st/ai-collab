package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 项目级模型路由网关。
 * 根据 projectId + purpose 查找项目专属的模型配置，不再回退到 .env 全局配置。
 */
@Component
@Primary
public class RoutingChatModelGateway implements ChatModelGateway {
    private final ModelConfigurationRepository configurations;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;
    private final ObjectMapper json;

    public RoutingChatModelGateway(
            ModelConfigurationRepository configurations,
            ModelSecretCipher secrets,
            List<ModelProviderAdapter> adapters,
            ObjectMapper json) {
        this.configurations = configurations;
        this.secrets = secrets;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.json = json;
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionCommand command) {
        ModelConfiguration configuration = resolveConfig(command);
        ModelCapabilityPolicy.require(configuration, command, false);
        ChatCompletionResult result = adapter(configuration).complete(
                configuration, secrets.decrypt(configuration.encryptedApiKey()), command);
        validateStructured(command, result);
        return result;
    }

    @Override
    public void completeStream(
            ChatCompletionCommand command,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        try {
            ModelConfiguration configuration = resolveConfig(command);
            ModelCapabilityPolicy.require(configuration, command, true);
            adapter(configuration).completeStream(
                    configuration, secrets.decrypt(configuration.encryptedApiKey()),
                    command, onToken, result -> {
                        try {
                            validateStructured(command, result);
                            onDone.accept(result);
                        } catch (Exception exception) {
                            onError.accept(exception);
                        }
                    }, onError);
        } catch (Exception exception) {
            onError.accept(exception);
        }
    }

    private ModelConfiguration resolveConfig(ChatCompletionCommand command) {
        if (command.projectId() == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "未指定项目，无法查找模型配置");
        }
        return configurations.findAssigned(command.projectId(), command.purpose())
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "项目未配置 " + command.purpose() + " 模型"));
    }

    private void validateStructured(ChatCompletionCommand command, ChatCompletionResult result) {
        if (command.outputFormat() != ChatCompletionCommand.OutputFormat.JSON_OBJECT) return;
        try {
            if (!json.readTree(result.content()).isObject()) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
    }

    private ModelProviderAdapter adapter(ModelConfiguration configuration) {
        if (!configuration.enabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        ModelProviderAdapter adapter = adapters.get(configuration.providerType());
        if (adapter == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return adapter;
    }
}
