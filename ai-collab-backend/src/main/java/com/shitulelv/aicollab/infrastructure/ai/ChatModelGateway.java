package com.shitulelv.aicollab.infrastructure.ai;

public interface ChatModelGateway {
    ChatCompletionResult complete(ChatCompletionCommand command);
}
