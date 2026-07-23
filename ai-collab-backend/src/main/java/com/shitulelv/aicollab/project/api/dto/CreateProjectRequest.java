package com.shitulelv.aicollab.project.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        LocalDate startDate,
        LocalDate dueDate) {
}
