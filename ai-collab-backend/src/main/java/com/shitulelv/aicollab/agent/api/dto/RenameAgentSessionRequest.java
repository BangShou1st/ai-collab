package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameAgentSessionRequest(
        @NotBlank @Size(max = 160) String title) {
}
