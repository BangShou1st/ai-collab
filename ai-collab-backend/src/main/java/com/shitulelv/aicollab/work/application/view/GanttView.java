package com.shitulelv.aicollab.work.application.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GanttView(
        List<GanttTask> tasks,
        List<GanttMilestone> milestones,
        List<GanttDependency> dependencies
) {
    public record GanttTask(
            UUID id,
            String title,
            String status,
            String assigneeName,
            LocalDate startDate,
            LocalDate dueDate,
            int sortOrder
    ) {}

    public record GanttMilestone(
            UUID id,
            String name,
            String status,
            LocalDate targetDate
    ) {}

    public record GanttDependency(
            UUID taskId,
            UUID dependsOnTaskId
    ) {}
}
