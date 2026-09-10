package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.List;
import org.springframework.stereotype.Component;

/** Dynamic free-model discovery. Never hardcode the free list; only *-free suffix is exposed. */
@Component
public class OpenCodeZenModelCatalog {
    private final OpenCodeZenTransport transport;
    public OpenCodeZenModelCatalog(OpenCodeZenTransport transport) { this.transport = transport; }
    public List<String> freeModels(String apiKey, AiRequestMetadata metadata) {
        return transport.listFreeModels(apiKey, metadata);
    }
    public void requireFreeModel(String model, List<String> currentFree) {
        if (model == null || model.isBlank() || currentFree == null || !currentFree.contains(model.strip()))
            throw new com.shitulelv.aicollab.common.exception.BusinessException(
                    com.shitulelv.aicollab.common.exception.ErrorCode.VALIDATION_ERROR, "model must be a current *-free model");
    }
}
