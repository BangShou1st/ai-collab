package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DashboardRecentTaskView(
        UUID id,
        String title,
        TaskStatus status,
        TaskPriority priority,
        UUID assigneeId,
        String assigneeDisplayName,
        UUID milestoneId,
        String milestoneName,
        LocalDate dueDate,
        int unfinishedDependencyCount,
        boolean overdue,
        OffsetDateTime updatedAt) {
}
