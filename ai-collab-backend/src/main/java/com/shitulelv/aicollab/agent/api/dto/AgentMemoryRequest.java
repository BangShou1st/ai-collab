package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AgentMemoryRequest(
        @NotBlank @Pattern(regexp="DECISION|PREFERENCE|CONSTRAINT|LESSON") String type,
        @NotBlank @Size(max=160) String title,
        @NotBlank @Size(max=2000) String content,
        @NotBlank @Size(max=32) String sourceType,
        UUID sourceId,
        int version) {
}
