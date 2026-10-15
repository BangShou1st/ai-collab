package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Zen wire boundary. Endpoint and identity come from {@link ProviderPresetRegistry}; only the session header name lives here. HTTP-only, no DB. */
public interface OpenCodeZenTransport {
    String SESSION_HEADER = "x-opencode-session";
    JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata);
    /** 免费可执行模型选择结果：Chat 可执行列表 + 因原生协议不受支持而排除的模型。 */
    ZenFreeModelSelection freeModelSelection(String apiKey, AiRequestMetadata metadata);
    default List<String> listFreeModels(String apiKey, AiRequestMetadata metadata) {
        return freeModelSelection(apiKey, metadata).models();
    }
    void validateCredential(String apiKey, String model, AiRequestMetadata metadata);
}
