package com.shitulelv.aicollab.planning.domain;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record ValidationContext(
        LocalDate projectStartDate,
        LocalDate projectDueDate,
        LocalDate planStartDate,
        LocalDate planDueDate,
        int maxTaskCount,
        Set<UUID> projectMemberIds,
        Set<String> existingTitles) {
    public ValidationContext {
        projectMemberIds = projectMemberIds == null ? Set.of() : Set.copyOf(projectMemberIds);
        existingTitles = existingTitles == null ? Set.of() : Set.copyOf(existingTitles);
    }
}
