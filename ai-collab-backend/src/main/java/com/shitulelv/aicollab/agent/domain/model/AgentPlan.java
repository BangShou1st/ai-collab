package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 执行计划。计划不是隐藏 chain-of-thought，只保存可展示的操作步骤。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentPlan(
        int version,
        String objective,
        List<AgentPlanStep> steps,
        List<String> successCriteria) {

    public AgentPlan {
        if (steps == null) steps = List.of();
        else steps = List.copyOf(steps);
        if (successCriteria == null) successCriteria = List.of();
        else successCriteria = List.copyOf(successCriteria);
        if (steps.size() > 6) throw new IllegalArgumentException("计划步骤不能超过 6 步");
    }

    public static AgentPlan create(String objective, List<AgentPlanStep> steps) {
        return new AgentPlan(1, objective, steps, List.of());
    }

    public AgentPlan withVersion(int newVersion) {
        return new AgentPlan(newVersion, objective, steps, successCriteria);
    }
}
