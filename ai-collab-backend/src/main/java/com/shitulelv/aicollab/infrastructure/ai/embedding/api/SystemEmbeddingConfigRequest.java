package com.shitulelv.aicollab.infrastructure.ai.embedding.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemEmbeddingConfigRequest(
        @NotBlank @Size(max = 64) String provider,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 512) String apiPath,
        @Size(max = 1000) String apiKey,
        @NotBlank @Size(max = 160) String modelName,
        @Min(1) @Max(4096) int dimensions,
        @Min(1) @Max(256) int batchSize) {
}
