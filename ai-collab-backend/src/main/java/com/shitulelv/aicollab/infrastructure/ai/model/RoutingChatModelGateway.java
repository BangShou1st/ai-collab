package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@Primary
public class RoutingChatModelGateway implements ChatModelGateway {
    private final ModelConfigurationRepository configurations;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;
    private final ChatModelGateway legacyFallback;
    private final ChatModelGateway planningFallback;
    private final ObjectMapper json;

    public RoutingChatModelGateway(
            ModelConfigurationRepository configurations,
            ModelSecretCipher secrets,
            List<ModelProviderAdapter> adapters,
            @Qualifier("openAiCompatibleChatModelGateway") ChatModelGateway legacyFallback,
            @Qualifier("planningFallbackChatModelGateway") ChatModelGateway planningFallback,
            ObjectMapper json) {
        this.configurations = configurations;
        this.secrets = secrets;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.legacyFallback = legacyFallback;
        this.planningFallback = planningFallback;
        this.json = json;
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionCommand command) {
        ModelConfiguration configuration = configurations.findAssigned(command.purpose()).orElse(null);
        if (configuration == null) {
            return fallback(command).complete(command);
        }
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
        ModelConfiguration configuration = configurations.findAssigned(command.purpose()).orElse(null);
        if (configuration == null) {
            fallback(command).completeStream(command, onToken, onDone, onError);
            return;
        }
        try {
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

    private ChatModelGateway fallback(ChatCompletionCommand command) {
        return command.purpose() == ModelPurpose.PLANNING
                ? planningFallback
                : legacyFallback;
    }
}
