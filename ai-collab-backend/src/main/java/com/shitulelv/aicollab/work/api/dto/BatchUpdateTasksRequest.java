package com.shitulelv.aicollab.work.api.dto;

import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record BatchUpdateTasksRequest(
        @NotNull List<TaskUpdateItem> items
) {
    public record TaskUpdateItem(
            @NotNull UUID taskId,
            Integer sortOrder,
            TaskStatus status,
            TaskPriority priority,
            UUID assigneeId,
            @NotNull Integer version
    ) {}
}
