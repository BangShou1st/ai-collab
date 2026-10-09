package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

public record ResolveAgentApprovalRequest(
        @NotBlank @Size(max = 100) String nonce,
        @Size(max = 500) String reason,
        @NotNull @Min(1) Integer expectedRevision) {
    public ResolveAgentApprovalRequest(String nonce, String reason) { this(nonce, reason, null); }
}
