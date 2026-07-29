package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;

import java.time.LocalDate;
import java.util.UUID;

public record DashboardProjectView(
        UUID id,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate,
        ProjectRole currentUserRole,
        Long memberCount) {
}
