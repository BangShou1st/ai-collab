package com.shitulelv.aicollab.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import java.util.Set;

/**
 * 场景目录响应。既有字段 {@code code} 保持不变；{@code scenario} 为 {@code code} 的同值别名，
 * {@code recommendedRoutes} 为 {@code entryRoutes} 的兼容别名（后续版本移除）。
 */
public record AgentScenarioResponse(
        String code, String displayName, String description,
        Set<String> entryRoutes,
        @Deprecated Set<String> recommendedRoutes,
        String scenario, String category, JsonNode inputSchema) {
    public static AgentScenarioResponse from(AgentScenario scenario) {
        Set<String> routes = scenario.entryRoutes();
        return new AgentScenarioResponse(scenario.code(), scenario.displayName(), scenario.description(),
                routes, routes, scenario.code(), scenario.category().name(), scenario.inputSchema());
    }
}
