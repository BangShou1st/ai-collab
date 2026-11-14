package com.shitulelv.aicollab.infrastructure.ai.model;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ProviderExtensionParamsProperties.class,
        GeminiThinkingProperties.class})
public class ModelTransportConfiguration {
}
