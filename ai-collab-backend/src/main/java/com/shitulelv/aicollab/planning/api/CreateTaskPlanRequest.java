package com.shitulelv.aicollab.planning.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateTaskPlanRequest(
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 2000) String goal,
        @Size(max = 4000) String constraints,
        @NotNull LocalDate planStartDate,
        @NotNull LocalDate planDueDate,
        int maxTaskCount,
        @Size(max = 10) List<UUID> documentIds) {}
