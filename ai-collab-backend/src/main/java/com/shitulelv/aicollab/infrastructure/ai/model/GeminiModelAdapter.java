package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GeminiModelAdapter extends ModelBridgeProviderAdapter {
    @Autowired
    public GeminiModelAdapter(
            ObjectMapper mapper,
            ModelBridgeClient client,
            GeminiThinkingProperties thinkingProperties) {
        super(ModelProviderType.GEMINI, mapper, client, thinkingProperties,
                ProviderExtensionParamsProperties.disabled());
    }

    GeminiModelAdapter(ObjectMapper mapper, ModelBridgeClient client) {
        this(mapper, client, GeminiThinkingProperties.disabled());
    }
}
