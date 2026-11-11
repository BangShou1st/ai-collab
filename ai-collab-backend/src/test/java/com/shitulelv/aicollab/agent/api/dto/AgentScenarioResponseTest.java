package com.shitulelv.aicollab.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioCategory;
import com.shitulelv.aicollab.agent.domain.model.builtin.MeetingToTasksScenario;
import com.shitulelv.aicollab.agent.domain.model.builtin.ProjectHealthScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScenarioResponseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepsCodeAndAddsScenarioCategoryAndRouteAliases() {
        AgentScenario scenario = new MeetingToTasksScenario();
        AgentScenarioResponse response = AgentScenarioResponse.from(scenario);

        assertThat(response.code()).isEqualTo("MEETING_TO_TASKS");
        assertThat(response.scenario()).isEqualTo("MEETING_TO_TASKS").isEqualTo(response.code());
        assertThat(response.category()).isEqualTo(AgentScenarioCategory.WORKFLOW.name());
        assertThat(response.entryRoutes()).containsExactlyInAnyOrderElementsOf(scenario.entryRoutes());
        assertThat(response.recommendedRoutes()).isEqualTo(response.entryRoutes());
        assertThat(response.inputSchema()).isEqualTo(scenario.inputSchema());
    }

    @Test
    void serializesCompatibilityAliasesAlongsideCode() {
        AgentScenarioResponse response = AgentScenarioResponse.from(new MeetingToTasksScenario());
        JsonNode payload = json.valueToTree(response);

        assertThat(payload.path("code").asText()).isEqualTo("MEETING_TO_TASKS");
        assertThat(payload.path("scenario").asText()).isEqualTo("MEETING_TO_TASKS");
        assertThat(payload.path("category").asText()).isEqualTo("WORKFLOW");
        assertThat(payload.path("entryRoutes").isArray()).isTrue();
        assertThat(payload.path("entryRoutes")).isEqualTo(payload.path("recommendedRoutes"));
        assertThat(payload.path("inputSchema").isObject()).isTrue();
    }

    @Test
    void fixedScenarioUsesScenarioCategory() {
        AgentScenarioResponse response = AgentScenarioResponse.from(new ProjectHealthScenario());

        assertThat(response.code()).isEqualTo("PROJECT_HEALTH");
        assertThat(response.scenario()).isEqualTo("PROJECT_HEALTH");
        assertThat(response.category()).isEqualTo(AgentScenarioCategory.SCENARIO.name());
    }
}
