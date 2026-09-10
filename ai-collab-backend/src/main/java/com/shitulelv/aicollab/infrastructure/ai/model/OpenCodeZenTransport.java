package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Zen wire boundary. Owns base URL, User-Agent, bearer + x-opencode-session policy. HTTP-only, no DB. */
public interface OpenCodeZenTransport {
    String BASE_URL = "https://opencode.ai/zen/v1";
    String USER_AGENT = "opencode/1.18.21";
    String SESSION_HEADER = "x-opencode-session";
    String MODELS_PATH = "/models";
    String COMPLETIONS_PATH = "/chat/completions";
    JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata);
    List<String> listFreeModels(String apiKey, AiRequestMetadata metadata);
    void validateCredential(String apiKey, String model, AiRequestMetadata metadata);
}
