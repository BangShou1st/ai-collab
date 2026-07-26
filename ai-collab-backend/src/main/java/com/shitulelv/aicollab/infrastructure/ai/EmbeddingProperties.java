package com.shitulelv.aicollab.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("embedding")
public record EmbeddingProperties(
        boolean enabled,
        String provider,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        int dimensions,
        int batchSize,
        Duration connectTimeout,
        Duration readTimeout) {
}
