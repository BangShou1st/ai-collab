package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;

import java.util.function.Consumer;

public interface ModelProviderAdapter {
    ModelProviderType providerType();

    ChatCompletionResult complete(ModelConfiguration configuration, String apiKey, ChatCompletionCommand command);

    void completeStream(
            ModelConfiguration configuration,
            String apiKey,
            ChatCompletionCommand command,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError);
}
