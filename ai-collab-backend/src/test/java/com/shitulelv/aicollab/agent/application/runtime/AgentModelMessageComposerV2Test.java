package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Composer v2 分层组装：
 * 必选层预留、当前请求保护、大结果确定性投影、去重与协议配对。
 */
class AgentModelMessageComposerV2Test {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AgentRepository repository;
    private AgentMemoryService memories;
    private AgentModelMessageComposer composer;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        memories = mock(AgentMemoryService.class);
        when(repository.workingState(any(), any())).thenReturn(null);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<>());
        when(repository.citationsStillValid(any(), any())).thenReturn(true);
        when(memories.context(any())).thenReturn(List.of());
        composer = new AgentModelMessageComposer(repository, memories, json, mock(RoutingAgentModelExecutor.class));
    }

    @Test
    void composeV2KeepsMessageOrderAndInjectsCurrentRequest() {
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "第一轮：请查询项目全部任务并整理成列表给我确认一遍。", 1),
                message("ASSISTANT", "第一轮查询完成：项目内共有 6 项任务，已按优先级整理成列表返回。", 2)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        var composition = composer.composeV2(run("把结果改成表格"), context(), skill(),
                AgentPlan.create("查询任务", List.of()), List.of(), 30_000, 1.0);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages().get(0)).isInstanceOf(ModelMessage.System.class);
        // 当前请求注入在历史之后，且完整入选
        assertThat(userContents(composition.messages())).contains("把结果改成表格");
        assertThat(composition.stats().historyIncluded()).isEqualTo(2);
    }

    @Test
    void composeV2DoesNotDuplicateGoalAlreadyInHistory() {
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "查询项目任务并汇总本周的状态变化情况。", 1),
                message("ASSISTANT", "查询结果如下：本周新增 2 项任务，完成 1 项。", 2)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        var composition = composer.composeV2(run("查询项目任务并汇总本周的状态变化情况。"), context(), skill(),
                AgentPlan.create("查询任务", List.of()), List.of(), 30_000, 1.0);

        assertThat(userContents(composition.messages()).stream()
                .filter("查询项目任务并汇总本周的状态变化情况。"::equals)
                .count()).isEqualTo(1);
    }

    @Test
    void currentRequestSurvivesTinyHistoryBudgetButNotRequiredLayerOverflow() {
        // 历史预算被压到几乎为零时，当前请求仍然完整入选
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "很长的历史消息。".repeat(200), 1)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        var composition = composer.composeV2(run("日期不要改，最多十项"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 1.0);

        assertThat(composition.failureReason()).isNull();
        assertThat(userContents(composition.messages())).contains("日期不要改，最多十项");

        // 预算小到连必选层都放不下当前请求：明确停止而不是静默截断
        var overflow = composer.composeV2(run("必须完整保留的请求"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 10, 1.0);
        assertThat(overflow.failureReason())
                .isEqualTo(AgentModelMessageComposer.FAILURE_CURRENT_REQUEST_OVER_BUDGET);
    }

    @Test
    void duplicateOlderMessagesAreDeduplicated() {
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "查询项目任务并汇总本周的状态变化情况。", 1),
                message("ASSISTANT", "查询结果如下：本周新增 2 项任务，完成 1 项。", 2),
                message("USER", "查询项目任务并汇总本周的状态变化情况。", 3)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        var composition = composer.composeV2(run("查询项目任务并汇总本周的状态变化情况。"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0);

        assertThat(userContents(composition.messages()).stream()
                .filter("查询项目任务并汇总本周的状态变化情况。"::equals)
                .count()).isEqualTo(1);
        assertThat(composition.stats().historyIncluded()).isEqualTo(2);
    }

    @Test
    void largeToolOutputsAreProjectedDeterministicallyAndStayPaired() {
        ObjectNode bigOutput = json.createObjectNode();
        bigOutput.put("status", "SUCCESS");
        var items = bigOutput.putArray("items");
        for (int i = 0; i < 500; i++) {
            items.addObject().put("id", "task-" + i).put("title", "任务 " + i);
        }
        AgentStepView step = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "get_tasks", json.createObjectNode().put("toolCallId", "call-1"), bigOutput,
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run("查询全部任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(step), 30_000, 1.0);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.stats().toolResultsProjected()).isEqualTo(1);
        // 协议配对完整：Assistant(toolCall) 紧跟 ToolResult，toolCallId 一致
        List<ModelMessage> messages = composition.messages();
        assertThat(messages).filteredOn(m -> m instanceof ModelMessage.Assistant a && !a.toolCalls().isEmpty()).hasSize(1);
        assertThat(messages).filteredOn(m -> m instanceof ModelMessage.ToolResult).hasSize(1);
        ModelMessage.Assistant assistant = (ModelMessage.Assistant) messages.stream()
                .filter(m -> m instanceof ModelMessage.Assistant a && !a.toolCalls().isEmpty()).findFirst().orElseThrow();
        ModelMessage.ToolResult result = (ModelMessage.ToolResult) messages.stream()
                .filter(m -> m instanceof ModelMessage.ToolResult).findFirst().orElseThrow();
        assertThat(assistant.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCallId()).isEqualTo("call-1");
        // 投影保留标量与计数，不伪造完整列表
        JsonNode projected = result.result();
        assertThat(projected.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(projected.path("items").size()).isEqualTo(AgentModelMessageComposer.PROJECTION_ITEMS + 1);
        assertThat(projected.path("items").get(AgentModelMessageComposer.PROJECTION_ITEMS).path("projectedTotalCount").asInt())
                .isEqualTo(500);
        assertThat(projected.path("projection").asText()).isEqualTo("DETERMINISTIC");
    }

    @Test
    void smallToolOutputsRemainUnprojected() {
        AgentStepView step = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "get_tasks", json.createObjectNode().put("toolCallId", "call-1"),
                json.createObjectNode().put("status", "SUCCESS").put("count", 2),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run("查询任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(step), 30_000, 1.0);

        assertThat(composition.stats().toolResultsProjected()).isZero();
        ModelMessage.ToolResult result = (ModelMessage.ToolResult) composition.messages().stream()
                .filter(m -> m instanceof ModelMessage.ToolResult).findFirst().orElseThrow();
        assertThat(result.result().path("count").asInt()).isEqualTo(2);
    }

    @Test
    void memoryIsSkippedWhenItDoesNotFitRemainingBudget() {
        // 历史占满剩余预算，超大的项目记忆放不下时被跳过
        List<AgentMessageView> history = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            history.add(message("USER", ("历史消息 %d：".formatted(i)) + "项目相关内容。".repeat(60), i + 1));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);
        when(memories.context(any())).thenReturn(List.of(new AgentMemoryView(
                UUID.randomUUID(), UUID.randomUUID(), "PREFERENCE", "长记忆",
                "很长的记忆内容。".repeat(800), "MANUAL", null, "ACTIVE",
                UUID.randomUUID(), null, 1, OffsetDateTime.now(), OffsetDateTime.now())));

        var composition = composer.composeV2(run("继续完成任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 1.0);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.stats().memoryIncluded()).isFalse();
        assertThat(composition.messages()).noneMatch(m -> m instanceof ModelMessage.User user
                && user.content().startsWith("<UNTRUSTED_PROJECT_MEMORY>"));
        // 预算内历史仍被尽量保留
        assertThat(composition.stats().historyIncluded()).isGreaterThan(0);
    }

    @Test
    void legacyPathStillComposesFixedWindowHistory() {
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "第一轮：请查询项目全部任务并整理成列表给我确认一遍。", 1),
                message("ASSISTANT", "第一轮查询完成：项目内共有 6 项任务，已按优先级整理。", 2)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        List<ModelMessage> messages = composer.buildMessageHistory(run("新目标"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of());

        assertThat(messages.get(0)).isInstanceOf(ModelMessage.System.class);
        assertThat(userContents(messages)).contains("新目标");
    }

    /**
     * 回退路径回归：当前请求存在于取回的历史但落在固定窗口之外（或被预算跳过）时，
     * 必须按"实际入选消息"判断并补入当前请求，不得因"取回列表中存在"而跳过注入。
     */
    @Test
    void legacyPathInjectsCurrentRequestWhenItIsNotAmongSelectedMessages() {
        // 目标消息是最早的第 1 条：取回 10 条，但固定窗口只选最后 6 条，目标不在其中
        List<AgentMessageView> history = new ArrayList<>();
        history.add(message("USER", "不改日期，最多十项任务", 1));
        for (int i = 2; i <= 10; i++) {
            history.add(message("ASSISTANT", "第 " + i + " 轮查询的完整结果与说明，用于占位填充固定窗口。", i));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        List<ModelMessage> messages = composer.buildMessageHistory(run("不改日期，最多十项任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of());

        assertThat(userContents(messages)).contains("不改日期，最多十项任务");
    }

    @Test
    void legacyPathInjectsCurrentRequestWhenBudgetSkippedItsWindowEntry() {
        // 目标在固定窗口内但内容超预算被整条跳过：同样必须补入当前请求
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "超长消息占满历史预算。".repeat(1200), 1),
                message("ASSISTANT", "后续回复内容。", 2)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        List<ModelMessage> messages = composer.buildMessageHistory(run("日期不要改"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of());

        assertThat(userContents(messages)).contains("日期不要改");
    }

    @Test
    void composeV2InjectsExistingConversationSummary() {
        var state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 9);
        state.put("goalRevision", 1);
        state.put("activeGoal", "整理任务");
        var summary = state.putObject("summary");
        summary.put("schemaVersion", 1);
        summary.put("sourceFrom", UUID.randomUUID().toString());
        summary.put("sourceThrough", UUID.randomUUID().toString());
        summary.put("text", "早期对话摘要：用户要求不改日期、最多十项。");
        when(repository.workingState(any(), any())).thenReturn(state);

        var composition = composer.composeV2(run("继续"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages()).anyMatch(m -> m instanceof ModelMessage.User user
                && user.content().startsWith("<CONVERSATION_SUMMARY")
                && user.content().contains("不改日期")
                && user.content().contains("不是当前事实或权限"));
    }

    @Test
    void composeV2ExposesUnpickedHistoryAsSummaryCandidates() {
        List<AgentMessageView> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(message("USER", ("历史消息 %d：".formatted(i)) + "项目相关内容。".repeat(60), i + 1));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        // 极小预算：只有最近几条能入选，更早的消息成为摘要候选
        var composition = composer.composeV2(run("继续"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 0.4);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.summaryCandidates()).isNotEmpty();
        // 候选按旧→新排列，最后一条是最新的未选中消息
        var candidates = composition.summaryCandidates();
        assertThat(candidates.get(candidates.size() - 1).createdAt())
                .isAfterOrEqualTo(candidates.get(0).createdAt());
        for (AgentMessageView candidate : candidates) {
            assertThat(composition.messages()).noneMatch(m -> m instanceof ModelMessage.User user
                    && user.content().equals(candidate.content()));
        }
    }

    /**
     * 回退验收（蓝图 8.1/P1-5）：已写入 v2 状态与摘要的会话，
     * 关闭 composer-v2 开关后走旧组装路径，仍能继续对话——
     * 不丢记录、不报错、不误认当前目标；约束无法完整表达时 v2 路径明确停止。
     */
    @Test
    void legacyFallbackPathUnderstandsV2WorkingStateAndSummary() {
        var state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 9);
        state.put("goalRevision", 2);
        state.put("activeGoal", "整理项目任务");
        var constraints = state.putArray("constraints");
        var constraint = constraints.addObject();
        constraint.put("id", UUID.randomUUID().toString());
        constraint.put("value", "不改日期，最多八项");
        constraint.put("status", "active");
        constraint.put("scope", "DATE_LOCK");
        constraint.put("sourceMessageId", UUID.randomUUID().toString());
        var summary = state.putObject("summary");
        summary.put("schemaVersion", 1);
        summary.put("sourceFrom", UUID.randomUUID().toString());
        summary.put("sourceThrough", UUID.randomUUID().toString());
        summary.put("text", "早期对话摘要：用户要求不改日期、最多八项。");
        when(repository.workingState(any(), any())).thenReturn(state);
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "第二轮请求：请把负责人改成小王。", 5),
                message("ASSISTANT", "已说明负责人为小王的查询结果。", 6)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        // 旧组装路径（composer-v2=false）读取 v2 状态：不报错、约束与摘要不丢失、目标不误认
        List<ModelMessage> legacyMessages = composer.buildMessageHistory(run("继续查详情"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of());

        assertThat(legacyMessages.get(0)).isInstanceOf(ModelMessage.System.class);
        String workingStateBlock = legacyMessages.stream()
                .filter(m -> m instanceof ModelMessage.User)
                .map(m -> ((ModelMessage.User) m).content())
                .filter(c -> c.startsWith("<CURRENT_WORKING_STATE>"))
                .findFirst().orElseThrow();
        assertThat(workingStateBlock).contains("整理项目任务");
        assertThat(workingStateBlock).contains("不改日期，最多八项");
        assertThat(workingStateBlock).contains("仍有效的用户约束");
        assertThat(legacyMessages).anyMatch(m -> m instanceof ModelMessage.User user
                && user.content().startsWith("<CONVERSATION_SUMMARY"));
        // 当前目标未被旧摘要误认：最新请求仍在
        assertThat(userContents(legacyMessages)).contains("继续查详情");
    }

    private List<String> userContents(List<ModelMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof ModelMessage.User)
                .map(m -> ((ModelMessage.User) m).content())
                .toList();
    }

    private AgentMessageView message(String role, String content, int sequence) {
        return new AgentMessageView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                role, content, null, null, OffsetDateTime.now().plusSeconds(sequence));
    }

    private AgentRunView run(String goal) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, goal, AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, false,
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
