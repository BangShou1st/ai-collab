package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import java.util.function.Consumer;

public interface ChatModelGateway {
    ChatCompletionResult complete(ChatCompletionCommand command);
    default ChatCompletionResult complete(ChatCompletionCommand command, AiRequestMetadata metadata) { return complete(command); }

    /**
     * 流式完成：每收到一个 token 就回调 onToken，完成后回调 onDone 返回元数据。
     * 如果出错，调用 onError 并保证 onDone 不再被调用。
     */
    void completeStream(
            ChatCompletionCommand command,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError);
    default void completeStream(ChatCompletionCommand command, AiRequestMetadata metadata,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone, Consumer<Exception> onError) {
        completeStream(command, onToken, onDone, onError);
    }
}
