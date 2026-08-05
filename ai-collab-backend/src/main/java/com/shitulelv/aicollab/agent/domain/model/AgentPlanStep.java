package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 计划步骤。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentPlanStep(
        String id,
        String title,
        String purpose,
        AgentPlanStepStatus status,
        List<String> expectedTools) {

    public AgentPlanStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("步骤 id 不能为空");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("步骤 title 不能为空");
        if (purpose == null) purpose = "";
        if (purpose.length() > 300) throw new IllegalArgumentException("步骤 purpose 不能超过 300 字");
        if (status == null) status = AgentPlanStepStatus.PENDING;
        if (expectedTools == null) expectedTools = List.of();
        else expectedTools = List.copyOf(expectedTools);
    }

    public AgentPlanStep withStatus(AgentPlanStepStatus newStatus) {
        return new AgentPlanStep(id, title, purpose, newStatus, expectedTools);
    }
}
