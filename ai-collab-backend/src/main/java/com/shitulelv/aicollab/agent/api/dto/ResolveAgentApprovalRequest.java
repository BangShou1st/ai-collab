package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveAgentApprovalRequest(
        @NotBlank @Size(max = 100) String nonce,
        @Size(max = 500) String reason) {
}
