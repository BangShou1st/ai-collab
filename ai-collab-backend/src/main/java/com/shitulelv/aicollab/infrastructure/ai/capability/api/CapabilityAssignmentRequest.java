package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** capability/purpose 在服务端固定为 RERANK/KNOWLEDGE_RERANK，避免客户端误绑 KNOWLEDGE_CHAT。 */
public record CapabilityAssignmentRequest(
        @NotNull UUID profileId,
        Boolean enabled,
        @Size(max = 500) String reason) {

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }
}
