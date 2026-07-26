package com.shitulelv.aicollab.work.api.dto;

import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateMilestoneRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        LocalDate targetDate,
        @NotNull MilestoneStatus status,
        @NotNull @Min(0) Integer sortOrder,
        @NotNull @Min(0) Integer version) {
}
