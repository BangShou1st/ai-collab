package com.shitulelv.aicollab.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Strict contract for skeleton generation output.
 * Contains only identity fields — no detail, no assigneeId, no dates beyond targetDate.
 * No sources — sources come from server context, not from the model.
 * Jackson will reject any unknown properties at deserialization time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SkeletonModelOutput(
        String summary,
        List<String> assumptions,
        List<String> risks,
        List<SkeletonMilestone> milestones,
        List<SkeletonTask> tasks) {

    public record SkeletonMilestone(
            String tempKey,
            String title,
            String objective,
            String targetDate,
            int sortOrder) {}

    public record SkeletonTask(
            String tempKey,
            String milestoneTempKey,
            String title,
            String objective,
            int sortOrder) {}
}
