package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitAgentMessageRequest(
        @NotBlank @Size(max = 4000) String content,
        @Size(max = 80) String skillCode,
        @Valid AgentPageContextRequest pageContext) {
}
