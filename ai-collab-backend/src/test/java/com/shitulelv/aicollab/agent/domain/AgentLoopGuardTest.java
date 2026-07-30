package com.shitulelv.aicollab.agent.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.domain.policy.AgentLoopGuard;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentLoopGuard guard = new AgentLoopGuard();

    @Test
    void rejectsThirdEquivalentToolCallWithoutProgress() throws Exception {
        JsonNode arguments = json.readTree("{\"status\":\"OPEN\"}");
        JsonNode output = json.readTree("{\"count\":3}");
        List<AgentStepView> steps = List.of(
                completed(1, "check_project_progress", arguments, output),
                completed(2, "check_project_progress", arguments, output));

        boolean noProgress = guard.hasNoProgress(
                steps,
                new AgentDecision.CallTool(
                        "check_project_progress", arguments, "再次检查"));

        assertThat(noProgress).isTrue();
    }

    @Test
    void allowsAChangedToolResult() throws Exception {
        JsonNode arguments = json.readTree("{\"status\":\"OPEN\"}");
        List<AgentStepView> steps = List.of(
                completed(1, "check_project_progress", arguments, json.readTree("{\"count\":3}")),
                completed(2, "check_project_progress", arguments, json.readTree("{\"count\":4}")));

        assertThat(guard.hasNoProgress(
                steps,
                new AgentDecision.CallTool(
                        "check_project_progress", arguments, "再次检查")))
                .isFalse();
    }

    @Test
    void allowsDifferentArguments() throws Exception {
        JsonNode output = json.readTree("{\"count\":3}");
        List<AgentStepView> steps = List.of(
                completed(1, "list_tasks", json.readTree("{\"status\":\"OPEN\"}"), output),
                completed(2, "list_tasks", json.readTree("{\"status\":\"OPEN\"}"), output));

        assertThat(guard.hasNoProgress(
                steps,
                new AgentDecision.CallTool(
                        "list_tasks", json.readTree("{\"status\":\"DONE\"}"), "换条件")))
                .isFalse();
    }

    private static AgentStepView completed(
            int sequence, String tool, JsonNode input, JsonNode output) {
        return new AgentStepView(
                UUID.randomUUID(), sequence, AgentStepType.TOOL_CALL_COMPLETED,
                tool, input, output, "test", 10, 5, false, 2,
                null, OffsetDateTime.now());
    }
}
