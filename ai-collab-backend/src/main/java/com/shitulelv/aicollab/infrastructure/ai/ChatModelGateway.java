package com.shitulelv.aicollab.infrastructure.ai;

import java.util.function.Consumer;

public interface ChatModelGateway {
    ChatCompletionResult complete(ChatCompletionCommand command);

    /**
     * 流式完成：每收到一个 token 就回调 onToken，完成后回调 onDone 返回元数据。
     * 如果出错，调用 onError 并保证 onDone 不再被调用。
     */
    void completeStream(
            ChatCompletionCommand command,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError);
}
