package com.shitulelv.aicollab.infrastructure.ai.capability;

public record ResolvedRerankConfiguration(
        CapabilityAssignment assignment,
        CapabilityProfile profile,
        CapabilityProvider provider,
        RerankSettings settings) {
}
