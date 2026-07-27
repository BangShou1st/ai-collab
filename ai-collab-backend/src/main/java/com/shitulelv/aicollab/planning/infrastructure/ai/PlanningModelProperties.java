package com.shitulelv.aicollab.planning.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("planning")
public record PlanningModelProperties(
        Boolean enabled, String provider, String baseUrl, String path, String apiKey, String model,
        Duration connectTimeout, Duration readTimeout, Double temperature, Integer maxOutputTokens,
        int maxSources, int sourceCodepointBudget, double retrievalThreshold,
        int generationLimitPerUserHour) {}
