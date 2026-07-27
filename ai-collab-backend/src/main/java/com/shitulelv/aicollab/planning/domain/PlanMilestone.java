package com.shitulelv.aicollab.planning.domain;

import java.time.LocalDate;
import java.util.List;

public record PlanMilestone(
        String tempKey,
        String title,
        String objective,
        String description,
        LocalDate targetDate,
        int sortOrder,
        List<String> sourceRefs) {
    public PlanMilestone {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
