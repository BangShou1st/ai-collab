package com.shitulelv.aicollab.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import java.util.Set;

public record AgentSkillResponse(
        String code, String displayName, String description,
        Set<String> recommendedRoutes, JsonNode inputSchema) {
    public static AgentSkillResponse from(AgentSkill skill) {
        return new AgentSkillResponse(skill.code(), skill.displayName(), skill.description(),
                skill.recommendedRoutes(), skill.inputSchema());
    }
}
