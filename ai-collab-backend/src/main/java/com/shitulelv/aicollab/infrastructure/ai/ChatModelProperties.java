package com.shitulelv.aicollab.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("chat")
public record ChatModelProperties(
        boolean enabled,
        String provider,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration readTimeout,
        double temperature,
        int maxOutputTokens,
        @DefaultValue("true") boolean jsonModeEnabled) {
}
