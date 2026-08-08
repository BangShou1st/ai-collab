package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContinueAgentRunRequest(
        @NotBlank @Size(max = 4000) String content) {
}
