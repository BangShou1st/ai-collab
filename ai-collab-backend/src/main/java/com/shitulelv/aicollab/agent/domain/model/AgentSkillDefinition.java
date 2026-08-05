package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Skill 定义记录，用于注册表。
 */
public record AgentSkillDefinition(
        String code,
        String displayName,
        String description,
        Set<String> recommendedRoutes,
        Set<String> allowedTools,
        JsonNode inputSchema,
        boolean allowWriteTools,
        AgentRuntimeLimits defaultLimits,
        String instruction,
        String outputContract) implements AgentSkill {

    public AgentSkillDefinition {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Skill code 不能为空");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Skill displayName 不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Skill description 不能为空");
        if (allowedTools == null || allowedTools.isEmpty()) throw new IllegalArgumentException("Skill allowedTools 不能为空");
        if (defaultLimits == null) throw new IllegalArgumentException("Skill defaultLimits 不能为空");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("Skill instruction 不能为空");
        if (outputContract == null || outputContract.isBlank()) throw new IllegalArgumentException("Skill outputContract 不能为空");
        recommendedRoutes = recommendedRoutes == null ? Set.of() : Set.copyOf(recommendedRoutes);
        allowedTools = Set.copyOf(allowedTools);
        if (inputSchema == null) inputSchema = new ObjectMapper().createObjectNode();
    }
}
