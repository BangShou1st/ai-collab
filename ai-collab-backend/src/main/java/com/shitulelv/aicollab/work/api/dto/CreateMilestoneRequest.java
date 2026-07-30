package com.shitulelv.aicollab.work.api.dto;

import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateMilestoneRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate targetDate,
        MilestoneStatus status,
        @Min(0) Integer sortOrder) {
}
