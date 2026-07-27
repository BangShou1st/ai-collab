package com.shitulelv.aicollab.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * Strict contract for skeleton generation output.
 * Contains only identity fields — no detail, no assigneeId, no dates beyond targetDate.
 * Jackson will reject any unknown properties at deserialization time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SkeletonModelOutput(
        String summary,
        List<String> assumptions,
        List<String> risks,
        List<SkeletonMilestone> milestones,
        List<SkeletonTask> tasks,
        List<SkeletonSource> sources) {

    public record SkeletonMilestone(
            String tempKey,
            String title,
            String objective,
            String targetDate,
            int sortOrder,
            List<String> sourceRefs) {}

    public record SkeletonTask(
            String tempKey,
            String milestoneTempKey,
            String title,
            String objective,
            int sortOrder) {}

    public record SkeletonSource(
            String ref) {}
}
