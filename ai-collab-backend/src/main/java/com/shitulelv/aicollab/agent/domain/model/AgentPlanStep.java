package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 参考步骤。status 与 expectedTools 仅保留用于读取既有计划，不推进工具执行。
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

}
