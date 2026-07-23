package com.shitulelv.aicollab.project.api.dto;

import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        LocalDate startDate,
        LocalDate dueDate,
        ProjectStatus status,
        @NotNull @Min(0) Integer version) {
}
