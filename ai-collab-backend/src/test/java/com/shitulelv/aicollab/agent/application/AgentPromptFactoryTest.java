package com.shitulelv.aicollab.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPromptFactoryTest {
    private final AgentPromptFactory prompts = new AgentPromptFactory();

    @Test
    void providesExactCallToolDecisionShape() {
        String prompt = prompts.systemPrompt(
                List.of(AgentToolDefinition.openObject(
                        "check_project_progress", "检查项目进度", false)),
                "SUPERVISOR");

        assertThat(prompt)
                .contains("\"action\":\"call_tool\"")
                .contains("\"tool\":\"check_project_progress\"")
                .contains("\"arguments\":{}")
                .contains("工具名绝不能放入 action");
    }

    @Test
    void includesCreateTaskInputSchema() throws Exception {
        JsonNode schema = new ObjectMapper().readTree("""
                {"type":"object","additionalProperties":false,
                 "required":["title"],
                 "properties":{
                   "title":{"type":"string","maxLength":160},
                   "priority":{"type":["string","null"],"enum":["LOW","MEDIUM","HIGH","URGENT",null]},
                   "dueDate":{"type":["string","null"],"format":"date"}}}
                """);
        AgentToolDefinition tool = new AgentToolDefinition(
                "create_task_after_approval", "创建待审批任务", schema, true);

        String prompt = prompts.systemPrompt(List.of(tool), "SUPERVISOR");

        assertThat(prompt)
                .contains("create_task_after_approval")
                .contains("\"priority\":{\"type\":[\"string\",\"null\"]")
                .contains("\"dueDate\":{\"type\":[\"string\",\"null\"],\"format\":\"date\"}")
                .contains("\"additionalProperties\":false");
    }

    @Test
    void includesPreviousDecisionErrorInCorrectionPrompt() {
        AgentStepView invalid = new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.ERROR, null,
                null, null, "未知 Agent action: check_project_progress",
                100, 16, false, 12, "AGENT_INVALID_DECISION", OffsetDateTime.now());

        String prompt = prompts.userPrompt("检查项目进度", List.of(), List.of(invalid));

        assertThat(prompt)
                .contains("<CORRECTION_REQUIRED>")
                .contains("未知 Agent action: check_project_progress")
                .contains("</CORRECTION_REQUIRED>");
    }
}
