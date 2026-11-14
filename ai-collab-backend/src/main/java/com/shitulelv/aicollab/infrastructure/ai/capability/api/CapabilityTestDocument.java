package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CapabilityTestDocument(
        @NotBlank @Size(max = 128) String id,
        @Size(max = 500) String title,
        @NotBlank @Size(max = 8192) String text) {
}
