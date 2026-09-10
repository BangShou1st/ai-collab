package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Zen wire boundary. Endpoint and identity come from {@link ProviderPresetRegistry}; only the session header name lives here. HTTP-only, no DB. */
public interface OpenCodeZenTransport {
    String SESSION_HEADER = "x-opencode-session";
    JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata);
    List<String> listFreeModels(String apiKey, AiRequestMetadata metadata);
    void validateCredential(String apiKey, String model, AiRequestMetadata metadata);
}
