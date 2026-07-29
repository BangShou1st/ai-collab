package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitAgentMessageRequest(
        @NotBlank @Size(max = 4000) String content) {
}
