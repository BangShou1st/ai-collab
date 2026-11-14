package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.AiInvocationContext;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 用户级模型路由网关。
 * 根据 AiInvocationContext(userId + purpose) 解析调用者个人模型配置，projectId 仅为业务上下文。
 */
@Component
@Primary
public class RoutingChatModelGateway implements ChatModelGateway {
    private final UserAiProviderService userProviders;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;
    private final ObjectMapper json;

    public RoutingChatModelGateway(
            UserAiProviderService userProviders,
            ModelSecretCipher secrets,
            List<ModelProviderAdapter> adapters,
            ObjectMapper json) {
        this.userProviders = userProviders;
        this.secrets = secrets;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.json = json;
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionCommand command) {
        return complete(command, AiRequestMetadata.fresh());
    }

    public ChatCompletionResult complete(ChatCompletionCommand command, AiRequestMetadata metadata) {
        UserAiProvider provider = resolveProvider(command);
        return executeResolved(command, metadata, provider);
    }

    public ChatCompletionResult completeWithSnapshot(ChatCompletionCommand command, AiRequestMetadata metadata,
            UserAiProvider snapshot, Integer budget) {
        if (!isShared(snapshot) && !snapshot.userId().equals(command.callerUserId())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        userProviders.requireSnapshotAuthorized(snapshot);
        int effectiveBudget = budget != null && budget > 0 ? budget : snapshot.maxOutputTokens();
        UserAiProvider effective = new UserAiProvider(snapshot.id(), snapshot.userId(), snapshot.name(), snapshot.providerType(),
                snapshot.baseUrl(), snapshot.apiPath(), snapshot.encryptedApiKey(), snapshot.modelName(), snapshot.enabled(),
                snapshot.temperature(), effectiveBudget, snapshot.capabilities(), snapshot.isDefault(), snapshot.createdAt(), snapshot.updatedAt(), snapshot.shared(),
                snapshot.capabilityConfigVersion());
        return executeResolved(command, metadata, effective);
    }

    private ChatCompletionResult executeResolved(ChatCompletionCommand command, AiRequestMetadata metadata, UserAiProvider provider) {
        ModelConfiguration configuration = provider.toModelConfiguration();
        if (!CapabilityProbeContext.activeFor(provider)) {
            ModelCapabilityPolicy.require(configuration, command, false);
        }
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
        completeStream(command, AiRequestMetadata.fresh(), onToken, onDone, onError);
    }

    public void completeStream(
            ChatCompletionCommand command, AiRequestMetadata metadata,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        try {
            UserAiProvider provider = resolveProvider(command);
            ModelConfiguration configuration = provider.toModelConfiguration();
            if (!CapabilityProbeContext.activeFor(provider)) {
                ModelCapabilityPolicy.require(configuration, command, true);
            }
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

    private UserAiProvider resolveProvider(ChatCompletionCommand command) {
        if (command.callerUserId() == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "未指定调用用户，无法查找模型配置");
        }
        AiInvocationContext context = new AiInvocationContext(command.callerUserId(), command.projectId(), command.purpose());
        UserAiProvider pinned = AiConfigurationContext.current();
        if (pinned != null) {
            if (!isShared(pinned) && !pinned.userId().equals(context.userId())) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
            }
            userProviders.requireSnapshotAuthorized(pinned);
            return pinned;
        }
        return userProviders.resolve(context.userId(), context.purpose());
    }

    private static boolean isShared(UserAiProvider provider) {
        return provider != null && provider.shared();
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
