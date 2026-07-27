package com.shitulelv.aicollab.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Strict contract for detail generation output.
 * Contains only supplementary fields keyed by tempKey — no skeleton identity fields.
 * Jackson will reject any unknown properties at deserialization time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DetailModelOutput(
        List<DetailMilestone> milestones,
        List<DetailTask> tasks) {

    public record DetailMilestone(
            String tempKey,
            String description,
            List<String> sourceRefs) {}

    public record DetailTask(
            String tempKey,
            String description,
            String priority,
            BigDecimal estimatedHours,
            LocalDate startDate,
            LocalDate dueDate,
            UUID suggestedAssigneeId,
            List<String> dependencyTempKeys,
            List<String> sourceRefs) {}
}
