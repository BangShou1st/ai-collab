package com.shitulelv.aicollab.work.application.view;

import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskView(
        UUID id, UUID projectId, String title, String description, UUID milestoneId, String milestoneName,
        UUID assigneeId, String assigneeDisplayName, TaskStatus status, TaskPriority priority,
        BigDecimal estimateHours, LocalDate startDate, LocalDate dueDate, int version,
        int unfinishedDependencyCount, List<UUID> dependencyIds) {
    public static TaskView from(TaskEntity entity, List<UUID> dependencyIds) {
        return new TaskView(entity.getId(), entity.getProjectId(), entity.getTitle(), entity.getDescription(),
                entity.getMilestoneId(), entity.getMilestoneName(), entity.getAssigneeId(),
                entity.getAssigneeDisplayName(), entity.getStatus(), entity.getPriority(),
                entity.getEstimateHours(), entity.getStartDate(), entity.getDueDate(), entity.getVersion(),
                entity.getUnfinishedDependencyCount() == null ? 0 : entity.getUnfinishedDependencyCount(),
                List.copyOf(dependencyIds));
    }
}
