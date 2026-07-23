package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectView(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        LocalDate startDate,
        LocalDate dueDate,
        ProjectStatus status,
        ProjectRole role,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
