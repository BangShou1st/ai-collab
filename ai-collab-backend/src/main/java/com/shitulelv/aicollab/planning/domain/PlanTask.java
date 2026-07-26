package com.shitulelv.aicollab.planning.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PlanTask(
        String tempKey,
        String milestoneTempKey,
        String title,
        String objective,
        String description,
        String priority,
        BigDecimal estimatedHours,
        LocalDate startDate,
        LocalDate dueDate,
        UUID suggestedAssigneeId,
        UUID assigneeId,
        List<String> dependencyTempKeys,
        List<String> sourceRefs,
        int sortOrder) {
    public PlanTask {
        dependencyTempKeys = dependencyTempKeys == null ? List.of() : List.copyOf(dependencyTempKeys);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
