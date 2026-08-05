package com.shitulelv.aicollab.agent.api.dto;

import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.UUID;

public record CreateAgentScheduleRequest(
        @NotNull UUID sessionId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 4000) String goal,
        @NotBlank @Pattern(regexp = "DAILY|WEEKLY") String frequency,
        @NotBlank @Size(max = 80) String timeZone,
        @NotNull LocalTime localTime,
        @Min(1) @Max(7) Integer weeklyDay,
        @Pattern(regexp="[A-Z][A-Z0-9_]{2,63}") String skillCode) {
}
