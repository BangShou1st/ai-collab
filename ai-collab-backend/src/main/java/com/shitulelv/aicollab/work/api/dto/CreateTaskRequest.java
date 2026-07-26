package com.shitulelv.aicollab.work.api.dto;

import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTaskRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 4000) String description,
        UUID milestoneId,
        UUID assigneeId,
        TaskStatus status,
        TaskPriority priority,
        @DecimalMin("0.5") @DecimalMax("80") BigDecimal estimateHours,
        LocalDate startDate,
        LocalDate dueDate) {
}
