package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AnthropicModelAdapter extends ModelBridgeProviderAdapter {
    @Autowired
    public AnthropicModelAdapter(ObjectMapper mapper, ModelBridgeClient client) {
        super(ModelProviderType.ANTHROPIC, mapper, client,
                GeminiThinkingProperties.disabled(),
                ProviderExtensionParamsProperties.disabled());
    }
}
