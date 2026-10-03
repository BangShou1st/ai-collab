package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 有界增量摘要：实际覆盖范围（分段偏移）、增量延续、持久化尝试标记、CAS 与记账。 */
class AgentContextSummarizerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AgentRepository repository;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentContextSummarizer summarizer;
    private final UUID attemptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        summarizer = new AgentContextSummarizer(repository, modelExecutor, json);
        when(repository.countSummaryAttempts(any(), any())).thenReturn(0);
        when(repository.beginSummaryAttempt(any())).thenReturn(attemptId);
    }

    @Test
    void summarizesBoundedCandidatesAndCommitsWithCasSnapshot() {
        List<AgentMessageView> fixedCandidates = candidates();
        stubState(v2State(12, 3, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("仍有效约束：不改日期"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(true);

        AgentRunView run = run();
        summarizer.maybeSummarize(run, composition(fixedCandidates), 10_000);

        // 尝试上限先经持久化计数校验
        verify(repository).countSummaryAttempts(eq(run.projectId()), eq(run.id()));
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModelWithoutTools(eq(run), messages.capture());
        assertThat(messages.getValue()).hasSize(2);
        assertThat(messages.getValue().get(0).toString()).contains("摘要器");
        assertThat(messages.getValue().get(1).toString()).contains("不调用任何工具");
        // CAS 提交携带生成时的 revision 快照；全部候选短消息完整覆盖
        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).commitConversationSummary(eq(run.projectId()), eq(run.sessionId()),
                eq(12), eq(3), summary.capture());
        assertThat(summary.getValue().path("sourceThrough").asText())
                .isEqualTo(fixedCandidates.get(fixedCandidates.size() - 1).id().toString());
        assertThat(summary.getValue().path("coverage").asText()).isEqualTo("FULL");
        assertThat(summary.getValue().path("segments").size()).isEqualTo(3);
        assertThat(summary.getValue().path("activeConstraints").size()).isEqualTo(1);
        assertThat(summary.getValue().path("text").asText()).contains("不改日期");
        // 单独记账：尝试开始与完成各落一条持久化记录，usage 缺失按保守估算
        verify(repository).beginSummaryAttempt(run);
        verify(repository).completeSummaryAttempt(eq(attemptId), eq("COMMITTED"), anyString(),
                anyInt(), anyInt(), eq(true), any());
    }

    @Test
    void longMessagesAreSegmentCoveredWhileShortOnesFullyCovered() {
        // 中间一条超过单条上限：按偏移分段覆盖 [0,600)，后续短消息仍然完整覆盖
        List<AgentMessageView> fixedCandidates = List.of(
                message("USER", "第一条：查询任务并整理。", 1),
                message("ASSISTANT", "前段内容。".repeat(160) + "尾部标记。".repeat(8), 2),
                message("USER", "第三条：改用表格展示。", 3));
        stubState(v2State(7, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("摘要内容"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);

        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);

        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).commitConversationSummary(any(), any(), anyInt(), anyInt(), summary.capture());
        // 覆盖分段：短消息全文 + 长消息 [0,600)；PARTIAL 且长消息未覆盖完
        JsonNode segments = summary.getValue().path("segments");
        assertThat(segments.size()).isEqualTo(3);
        assertThat(segments.get(0).path("to").asInt()).isEqualTo(fixedCandidates.get(0).content().length());
        assertThat(segments.get(1).path("from").asInt()).isZero();
        assertThat(segments.get(1).path("to").asInt()).isEqualTo(AgentContextSummarizer.SEGMENT_CHARS);
        assertThat(segments.get(2).path("to").asInt()).isEqualTo(fixedCandidates.get(2).content().length());
        assertThat(summary.getValue().path("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(summary.getValue().path("uncoveredMessageIds").size()).isEqualTo(1);
        // 提示词包含后续短消息与长消息片段，但不包含长消息未读尾部
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModelWithoutTools(any(), messages.capture());
        String prompt = messages.getValue().get(1).toString();
        assertThat(prompt).contains("第三条：改用表格展示");
        assertThat(prompt).contains("前段内容");
        assertThat(prompt).doesNotContain("尾部标记");
    }

    @Test
    void longMessageCoverageProgressesAcrossRunsUntilFullyCovered() {
        // 首条旧消息 875 字符：第一次运行覆盖 [0,600)，第二次 [600,875)，第三次无可新增则跳过
        AgentMessageView longMessage = message("USER", "很长的历史记录。".repeat(125), 1);
        List<AgentMessageView> fixedCandidates = List.of(longMessage);
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("第一批摘要"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);

        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);

        ArgumentCaptor<JsonNode> first = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository, times(1)).commitConversationSummary(any(), any(), anyInt(), anyInt(), first.capture());
        assertThat(first.getValue().path("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(first.getValue().path("segments").get(0).path("to").asInt())
                .isEqualTo(AgentContextSummarizer.SEGMENT_CHARS);

        // 第二次运行：从上次偏移继续
        ObjectNode state2 = v2State(6, 1, null);
        state2.set("summary", first.getValue());
        stubState(state2);
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("第二批摘要"));
        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);

        ArgumentCaptor<JsonNode> second = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository, times(2)).commitConversationSummary(any(), any(), anyInt(), anyInt(), second.capture());
        JsonNode secondSummary = second.getValue();
        assertThat(secondSummary.path("coverage").asText()).isEqualTo("FULL");
        assertThat(secondSummary.path("segments").size()).isEqualTo(1);
        assertThat(secondSummary.path("segments").get(0).path("from").asInt())
                .isZero();
        assertThat(secondSummary.path("segments").get(0).path("to").asInt())
                .isEqualTo(longMessage.content().length());

        // 第三次运行：全部已覆盖，无可新增覆盖 → 不再调用模型
        ObjectNode state3 = v2State(7, 1, null);
        state3.set("summary", secondSummary);
        stubState(state3);
        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);
        verify(modelExecutor, times(2)).callModelWithoutTools(any(), any());
        verify(repository, times(2)).beginSummaryAttempt(any());
    }

    @Test
    void previousSummaryIsIncludedInFullEvenBeyondEightHundredChars() {
        // 旧摘要尾部（第 800 字符之后）的关键决定必须完整进入本次请求，不得截断丢失
        String previousText = "开头信息：用户要求整理任务。"
                + "填充内容。".repeat(165)
                + "尾部关键决定：周报必须保留表格格式";
        assertThat(previousText.length()).isGreaterThan(800);
        ObjectNode state = v2State(9, 2, null);
        var previous = state.putObject("summary");
        previous.put("schemaVersion", 1);
        previous.put("sourceFrom", UUID.randomUUID().toString());
        previous.put("sourceThrough", UUID.randomUUID().toString());
        previous.put("text", previousText);
        stubState(state);
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("延续后的摘要"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModelWithoutTools(any(), messages.capture());
        assertThat(messages.getValue().get(1).toString()).contains("尾部关键决定：周报必须保留表格格式");
        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).commitConversationSummary(any(), any(), anyInt(), anyInt(), summary.capture());
        assertThat(summary.getValue().path("incorporatedPrevious").asBoolean()).isTrue();
        assertThat(summary.getValue().path("sourceFrom").asText()).isEqualTo(previous.path("sourceFrom").asText());
    }

    @Test
    void skipsWhenExistingSummaryAlreadyCoversAllCandidates() {
        // 已有摘要的分段已完整覆盖全部候选：无可新增覆盖 → 不再调用模型
        List<AgentMessageView> fixedCandidates = candidates();
        ObjectNode state = v2State(5, 1, null);
        var previous = state.putObject("summary");
        previous.put("schemaVersion", 1);
        previous.put("sourceFrom", UUID.randomUUID().toString());
        previous.put("sourceThrough", fixedCandidates.get(fixedCandidates.size() - 1).id().toString());
        previous.put("text", "旧摘要");
        var segments = previous.putArray("segments");
        for (AgentMessageView message : fixedCandidates) {
            var segment = segments.addObject();
            segment.put("messageId", message.id().toString());
            segment.put("from", 0);
            segment.put("to", message.content().length());
        }
        stubState(state);

        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);

        verify(repository, never()).beginSummaryAttempt(any());
        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
    }

    @Test
    void skipsWhenPersistentAttemptLimitAlreadyReached() {
        stubState(v2State(5, 1, null));
        when(repository.countSummaryAttempts(any(), any())).thenReturn(AgentContextSummarizer.MAX_ATTEMPTS_PER_RUN);

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        // 尝试上限是持久化校验：服务重启不能绕过
        verify(repository, never()).beginSummaryAttempt(any());
        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
    }

    @Test
    void skipsWhenRemainingBudgetCannotFitSummaryRequest() {
        stubState(v2State(5, 1, null));

        summarizer.maybeSummarize(run(), composition(candidates()), 10);

        verify(repository, never()).beginSummaryAttempt(any());
        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
    }

    @Test
    void blankOutputRecordsUsageAndMarksAttemptEmpty() {
        stubState(v2State(5, 1, null));
        // content 为空但带 toolCalls 的合法结果 → 触发 EMPTY 路径
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(new ModelTurnResult(
                "  ", List.of(new ModelToolCall("x", "list_tasks", json.createObjectNode())),
                ModelFinishReason.STOP, null, "test-provider", "test-model", 9L));

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository).completeSummaryAttempt(eq(attemptId), eq("EMPTY"), eq("test-model"),
                anyInt(), anyInt(), eq(true), any());
        verify(repository, never()).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void realUsageIsRecordedInsteadOfEstimate() {
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(new ModelTurnResult(
                "摘要内容", List.of(), ModelFinishReason.STOP, new ModelUsage(321, 45),
                "test-provider", "test-model", 11L));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository).completeSummaryAttempt(eq(attemptId), eq("COMMITTED"), eq("test-model"),
                eq(321), eq(45), eq(false), eq(11L));
    }

    @Test
    void casConflictDiscardsSummaryWithoutRetry() {
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("摘要内容"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(false);

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository).completeSummaryAttempt(eq(attemptId), eq("CAS_CONFLICT"), anyString(),
                anyInt(), anyInt(), anyBoolean(), any());
        // CAS 冲突后不再重试第二次模型调用
        verify(modelExecutor, times(1)).callModelWithoutTools(any(), any());
    }

    @Test
    void modelFailureNeverBreaksMainTurnAndRecordsAttempt() {
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any()))
                .thenThrow(new RuntimeException("provider exploded"));

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository).completeSummaryAttempt(eq(attemptId), eq("FAILED"), eq("unknown"),
                anyInt(), eq(0), eq(true), eq(null));
        verify(repository, never()).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
    }

    private void stubState(JsonNode state) {
        when(repository.workingState(any(), any())).thenReturn(state);
    }

    private ObjectNode v2State(int stateRevision, int goalRevision, String summaryThrough) {
        var state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", stateRevision);
        state.put("goalRevision", goalRevision);
        state.put("activeGoal", "整理任务");
        var constraints = state.putArray("constraints");
        var constraint = constraints.addObject();
        constraint.put("id", UUID.randomUUID().toString());
        constraint.put("value", "不改日期");
        constraint.put("status", "active");
        constraint.put("scope", "DATE_LOCK");
        if (summaryThrough != null) {
            var summary = state.putObject("summary");
            summary.put("schemaVersion", 1);
            summary.put("sourceThrough", summaryThrough);
            summary.put("text", "旧摘要");
        }
        return state;
    }

    private List<AgentMessageView> candidates() {
        return List.of(
                message("USER", "第一轮请求：查询项目全部任务并整理。", 1),
                message("ASSISTANT", "第一轮回复：共 6 项任务。", 2),
                message("USER", "第二轮请求：改用表格展示。", 3));
    }

    private AgentModelMessageComposer.Composition composition(List<AgentMessageView> candidates) {
        return new AgentModelMessageComposer.Composition(List.of(), AgentModelMessageComposer.CompositionStats.empty(),
                null, candidates);
    }

    private ModelTurnResult turn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP, null, "test-provider", "test-model", 15L);
    }

    private AgentMessageView message(String role, String content, int sequence) {
        return new AgentMessageView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                role, content, null, null, OffsetDateTime.now().plusSeconds(sequence));
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "整理任务", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }
}
