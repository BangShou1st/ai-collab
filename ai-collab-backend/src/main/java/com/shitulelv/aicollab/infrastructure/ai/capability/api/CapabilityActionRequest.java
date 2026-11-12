package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import jakarta.validation.constraints.Size;

public record CapabilityActionRequest(@Size(max = 500) String reason) {
}
