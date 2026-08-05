package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.policy.AgentToolPolicy;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void registryRejectsDuplicateAndUnknownTools() {
        AgentTool first = fake("list_tasks", false);
        assertThatThrownBy(() -> new AgentToolRegistry(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class);

        AgentToolRegistry registry = new AgentToolRegistry(List.of(first));
        assertThat(registry.find("list_tasks")).contains(first);
        assertThat(registry.find("run_sql")).isEmpty();
    }

    @Test
    void policyNeverAllowsForbiddenCapabilities() {
        AgentToolPolicy policy = new AgentToolPolicy();
        AgentToolContext member = new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, 0);

        for (String name : List.of(
                "run_sql", "http_request", "delete_project", "delete_document",
                "manage_members", "configure_key", "confirm_ai_plan", "auto_approve")) {
            assertThatThrownBy(() -> policy.requireAllowed(fake(name, false), member))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void projectMemberCanProposeButSpecialistCannotUseWriteTools() {
        AgentToolPolicy policy = new AgentToolPolicy();
        AgentTool write = fake("update_task_after_approval", true);

        policy.requireAllowed(write, new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", true, 0));
        assertThatThrownBy(() -> policy.requireAllowed(write, new AgentToolContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "RISK_REVIEWER", false, 1))).isInstanceOf(IllegalArgumentException.class);
    }

    private AgentTool fake(String name, boolean writesBusinessData) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public boolean writesBusinessData() { return writesBusinessData; }
            @Override public AgentToolResult execute(AgentToolContext context,
                                                     com.fasterxml.jackson.databind.JsonNode arguments) {
                return new AgentToolResult(json.createObjectNode(), List.of(), List.of());
            }
        };
    }
}
