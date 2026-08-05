package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/**
 * 页面上下文，来自前端提交。后端必须校验实体属于当前项目。
 */
public record AgentPageContext(
        String route,
        UUID selectedTaskId,
        UUID selectedMilestoneId,
        UUID selectedDocumentId,
        UUID selectedPlanId,
        Map<String, JsonNode> filters) {

    public AgentPageContext {
        if (filters == null) filters = Map.of();
        else filters = Map.copyOf(filters);
        if (filters.size() > 20) throw new IllegalArgumentException("filters 过多");
    }

    public AgentPageContext(
            String route, UUID selectedTaskId, UUID selectedMilestoneId,
            UUID selectedDocumentId, Map<String, JsonNode> filters) {
        this(route, selectedTaskId, selectedMilestoneId, selectedDocumentId, null, filters);
    }

    public boolean hasSelection() {
        return selectedTaskId != null || selectedMilestoneId != null
                || selectedDocumentId != null || selectedPlanId != null;
    }

    public static AgentPageContext empty() {
        return new AgentPageContext(null, null, null, null, null, Map.of());
    }
}
