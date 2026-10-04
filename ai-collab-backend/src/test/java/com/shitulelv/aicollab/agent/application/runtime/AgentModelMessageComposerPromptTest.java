package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原生 Tool Calling 系统提示的回答范围约束回归。 */
class AgentModelMessageComposerPromptTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentRepository repository;
    private AgentModelMessageComposer composer;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        when(repository.workingState(any(), any())).thenReturn(null);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new java.util.ArrayList<>());
        RoutingAgentModelExecutor modelExecutor = mock(RoutingAgentModelExecutor.class);
        composer = new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json, modelExecutor);
    }

    @Test
    void nativeSystemPromptContainsAnswerScopeConstraint() {
        String systemPrompt = buildSystemPrompt();

        assertThat(systemPrompt)
                .contains("回答范围")
                .contains("只回答标题、状态、负责人")
                .contains("不补充其他字段")
                .contains("工具结果与事件记录保持完整")
                .contains("不强制套用固定 JSON 模板");
    }

    @Test
    void toolFactsRemainInStepRebuildRegardlessOfScopeConstraint() {
        // 回答范围约束只作用于最终回答；步骤重建的 Tool Result 仍完整保留。
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        AgentPlan plan = AgentPlan.create("查询任务", List.of());
        AgentStepView step = new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "get_task", json.createObjectNode(),
                json.createObjectNode().put("title", "任务A").put("status", "TODO"),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());
        List<ModelMessage> messages = composer.buildMessageHistory(
                run, ctx, skill(), plan, List.of(step));

        assertThat(messages).filteredOn(m -> m instanceof ModelMessage.ToolResult).isNotEmpty();
    }

    private String buildSystemPrompt() {
        AgentRunView run = run();
        List<ModelMessage> messages = composer.buildMessageHistory(
                run, context(), skill(), AgentPlan.create("查询任务", List.of()), List.of());
        assertThat(messages.get(0)).isInstanceOf(ModelMessage.System.class);
        return ((ModelMessage.System) messages.get(0)).content();
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "只查询当前任务标题、状态和负责人", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private AgentSkill skill() {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.instruction()).thenReturn("只读查询指令");
        when(skill.outputContract()).thenReturn("自然语言回答");
        return skill;
    }
}
