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
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
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
        when(memories.context(any(),any(),any())).thenReturn(List.of());
        composer = new AgentModelMessageComposer(repository, memories, json);
    }

    @Test
    void composeV2KeepsMessageOrderAndInjectsCurrentRequest() {
        List<AgentMessageView> history = new ArrayList<>(List.of(
                message("USER", "第一轮：请查询项目全部任务并整理成列表给我确认一遍。", 1),
                message("ASSISTANT", "第一轮查询完成：项目内共有 6 项任务，已按优先级整理成列表返回。", 2)));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);

        var composition = composer.composeV2(run("把结果改成表格"), context(), skill(),
                AgentPlan.create("查询任务", List.of()), List.of(), 30_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages().get(0)).isInstanceOf(ModelMessage.System.class);
        assertThat(((ModelMessage.System) composition.messages().get(0)).content())
                .contains("当前注册工具定义", "历史助手回答和摘要可能包含错误", "检索未命中不等于全文不存在", "不从头续读全文");
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
                AgentPlan.create("查询任务", List.of()), List.of(), 30_000, 1.0, false);

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
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(userContents(composition.messages())).contains("日期不要改，最多十项");

        // 预算小到连必选层都放不下当前请求：明确停止而不是静默截断
        var overflow = composer.composeV2(run("必须完整保留的请求"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 10, 1.0, false);
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
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0, false);

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
        // 输入必须真正超过"最新一批"的保留上限才会触发确定性投影。
        // 新策略把该上限从 6000 放宽到 48000 字符（有空间时保留必要原文），
        // 因此夹具要显著超过它——否则"未触发投影"是正确行为，而不是缺陷。
        String longTitle = "任务".repeat(150);
        for (int i = 0; i < 500; i++) {
            items.addObject().put("id", "task-" + i).put("title", longTitle + i);
        }
        assertThat(bigOutput.toString().length())
                .as("夹具必须超过最新一批工具结果的保留上限")
                .isGreaterThan(AgentModelMessageComposer.NEWEST_TOOL_OUTPUT_CAP);
        AgentStepView step = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "get_tasks", json.createObjectNode().put("toolCallId", "call-1"), bigOutput,
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run("查询全部任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(step), 30_000, 1.0, false);

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
        assertThat(projected.path("items").size()).isEqualTo(AgentToolOutputProjector.PROJECTION_ITEMS + 1);
        assertThat(projected.path("items").get(AgentToolOutputProjector.PROJECTION_ITEMS).path("projectedTotalCount").asInt())
                .isEqualTo(500);
        assertThat(projected.path("projection").asText()).isEqualTo("DETERMINISTIC");
    }

    @Test
    void toolStepPreservesThoughtSignatureInComposedMessages() {
        ObjectNode input = json.createObjectNode()
                .put("toolCallId", "call-gemini-1")
                .put("thoughtSignature", "sig-encrypted-token-xyz");
        input.putObject("arguments").put("query", "architecture");

        AgentStepView step = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "search_knowledge", input,
                json.createObjectNode().put("status", "SUCCESS").put("docId", "doc-1"),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run("查询项目资料"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(step), 30_000, 1.0, false);

        ModelMessage.Assistant assistant = (ModelMessage.Assistant) composition.messages().stream()
                .filter(m -> m instanceof ModelMessage.Assistant a && !a.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(assistant.toolCalls()).hasSize(1);
        assertThat(assistant.toolCalls().getFirst().thoughtSignature()).isEqualTo("sig-encrypted-token-xyz");
    }

    @Test
    void smallToolOutputsRemainUnprojected() {
        AgentStepView step = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "get_tasks", json.createObjectNode().put("toolCallId", "call-1"),
                json.createObjectNode().put("status", "SUCCESS").put("count", 2),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run("查询任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(step), 30_000, 1.0, false);

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
        when(memories.context(any(),any(),any())).thenReturn(List.of(new AgentMemoryView(
                UUID.randomUUID(), UUID.randomUUID(), "PREFERENCE", "长记忆",
                "很长的记忆内容。".repeat(800), "MANUAL", null, "ACTIVE",
                UUID.randomUUID(), null, 1, OffsetDateTime.now(), OffsetDateTime.now(),
                "UNVERIFIED", null, null, null)));

        var composition = composer.composeV2(run("继续完成任务"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.stats().memoryIncluded()).isFalse();
        assertThat(composition.messages()).noneMatch(m -> m instanceof ModelMessage.User user
                && user.content().startsWith("<UNTRUSTED_PROJECT_MEMORY>"));
        // 预算内历史仍被尽量保留
        assertThat(composition.stats().historyIncluded()).isGreaterThan(0);
    }

    @Test
    void composeV2DeclaresCorrectionPrecedenceOverStaleGoal() {
        var state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 12);
        state.put("goalRevision", 0);
        state.put("activeGoal", "项目目标:2026-10-31 前完成灰度发布");
        var corrections = state.putArray("goalCorrections");
        var earlier = corrections.addObject();
        earlier.put("quote", "更正:交付日期从 2026-10-31 提前到 2026-10-24。");
        earlier.put("status", "active");
        earlier.put("sourceMessageId", UUID.randomUUID().toString());
        var later = corrections.addObject();
        later.put("quote", "再更正:预算调整为 25 人日。");
        later.put("status", "active");
        later.put("sourceMessageId", UUID.randomUUID().toString());
        when(repository.workingState(any(), any())).thenReturn(state);

        var composition = composer.composeV2(run("继续"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages()).anyMatch(m -> m instanceof ModelMessage.User user
                && user.content().contains("项目目标:2026-10-31 前完成灰度发布")
                && user.content().contains("以用户更正为准")
                // 多条独立更正按顺序全部提供，只取最后一条会让早期更正在其原始消息退出窗口后丢失
                && user.content().contains("交付日期从 2026-10-31 提前到 2026-10-24")
                && user.content().contains("预算调整为 25 人日"));
    }

    @Test
    void composeV2DropsSupersededCorrectionsAfterGoalReplacement() {
        var state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 14);
        state.put("goalRevision", 1);
        state.put("activeGoal", "新目标:11-15 发布独立模块");
        var corrections = state.putArray("goalCorrections");
        var superseded = corrections.addObject();
        superseded.put("quote", "更正:交付日期提前到 2026-10-24。");
        superseded.put("status", "superseded");
        superseded.put("supersededReason", "GOAL_REPLACED");
        superseded.put("sourceMessageId", UUID.randomUUID().toString());
        when(repository.workingState(any(), any())).thenReturn(state);

        var composition = composer.composeV2(run("继续"), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages()).noneMatch(m -> m instanceof ModelMessage.User user
                && user.content().contains("以用户更正为准"));
        assertThat(composition.messages()).noneMatch(m -> m instanceof ModelMessage.User user
                && user.content().contains("交付日期提前到 2026-10-24"));
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
                AgentPlan.create("查询", List.of()), List.of(), 30_000, 1.0, false);

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
                AgentPlan.create("查询", List.of()), List.of(), 4_000, 0.4, false);

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

    @Test
    void historicalAssistantClaimsRemainIntactButAreExplicitlyUnverified() {
        var previous = message("ASSISTANT", "全文不存在服务器费用；所有内容已经全部核查。", 1);
        var request = message("USER", "只核对已经读取部分的费用依据，不生成任务。", 2);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<>(List.of(previous, request)));
        var composition = composer.composeV2(run(request.content()), context(), skill(),
                AgentPlan.create("核对", List.of()), List.of(), 30_000, 1.0, false);
        assertThat(composition.failureReason()).isNull();
        assertThat(composition.messages()).anyMatch(m -> m instanceof ModelMessage.Assistant assistant
                && assistant.content().contains("UNVERIFIED_ASSISTANT_HISTORY")
                && assistant.content().contains(previous.id().toString())
                && assistant.content().contains(previous.content()));
        assertThat(userContents(composition.messages())).contains(request.content());
    }

    @Test
    void optionalToolShareNeverExceedsPostGoalRemainder() {
        // 当前请求约 6,750 token；可选工具结果很大。工具观察份额必须先扣除当前请求预留，
        // 否则会把组装总量推过 tokenBudget，触发协调器的 0.6 降级重组并误判超预算。
        String goal = "目".repeat(9_000);
        AgentStepView huge = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "list_tasks", json.createObjectNode().put("toolCallId", "call-1"),
                json.createObjectNode().put("data", "n".repeat(40_000)),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());

        var composition = composer.composeV2(run(goal), context(), skill(),
                AgentPlan.create("查询", List.of()), List.of(huge), 10_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        assertThat(composition.stats().tokensUsed())
                .as("可选工具观察不得挤占当前请求预算")
                .isLessThanOrEqualTo(10_000);
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
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private AgentScenario skill() {
        AgentScenario skill = mock(AgentScenario.class);
        when(skill.instruction()).thenReturn("只读查询指令");
        when(skill.outputContract()).thenReturn("自然语言回答");
        return skill;
    }
}
