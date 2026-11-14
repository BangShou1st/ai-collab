package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleModelAdapter extends ModelBridgeProviderAdapter {
    @Autowired
    public OpenAiCompatibleModelAdapter(
            ObjectMapper mapper,
            ModelBridgeClient client,
            ProviderExtensionParamsProperties extensionParams) {
        super(ModelProviderType.OPENAI_COMPATIBLE, mapper, client,
                GeminiThinkingProperties.disabled(), extensionParams);
    }

    public OpenAiCompatibleModelAdapter(ObjectMapper mapper, ModelBridgeClient client) {
        this(mapper, client, ProviderExtensionParamsProperties.disabled());
    }
}
