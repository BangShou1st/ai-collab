package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Skill 的参考步骤。真实执行与恢复以工具调用和业务操作记录为准。
 * version 与 successCriteria 保留以兼容既有 plan_json 和事件。
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

}
