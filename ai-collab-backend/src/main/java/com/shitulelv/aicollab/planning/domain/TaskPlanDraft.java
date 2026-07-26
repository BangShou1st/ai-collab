package com.shitulelv.aicollab.planning.domain;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record TaskPlanDraft(
        String summary,
        List<String> assumptions,
        List<String> risks,
        List<PlanMilestone> milestones,
        List<PlanTask> tasks,
        List<PlanSource> sources) {
    public TaskPlanDraft {
        assumptions = immutable(assumptions);
        risks = immutable(risks);
        milestones = immutable(milestones);
        tasks = immutable(tasks);
        sources = immutable(sources);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
