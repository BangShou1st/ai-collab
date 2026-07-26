package com.shitulelv.aicollab.planning.domain;

import java.util.List;

public record TaskPlanDraft(
        String summary,
        List<String> assumptions,
        List<String> risks,
        List<PlanMilestone> milestones,
        List<PlanTask> tasks,
        List<PlanSource> sources) {
    public TaskPlanDraft {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        risks = risks == null ? List.of() : List.copyOf(risks);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
