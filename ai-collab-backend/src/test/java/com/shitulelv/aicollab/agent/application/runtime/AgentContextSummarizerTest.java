package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 有界增量摘要：有界输入、CAS 提交、单独记账与预算门槛。 */
class AgentContextSummarizerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private AgentRepository repository;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentContextSummarizer summarizer;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        summarizer = new AgentContextSummarizer(repository, modelExecutor, json);
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

        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModelWithoutTools(eq(run), messages.capture());
        // 有界输入：系统提示 + 摘要指令，不携带工具
        assertThat(messages.getValue()).hasSize(2);
        assertThat(messages.getValue().get(0).toString()).contains("摘要器");
        assertThat(messages.getValue().get(1).toString()).contains("不调用任何工具");
        // CAS 提交携带生成时的 revision 快照；摘要节点含覆盖范围与约束快照
        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).commitConversationSummary(eq(run.projectId()), eq(run.sessionId()),
                eq(12), eq(3), summary.capture());
        assertThat(summary.getValue().path("sourceThrough").asText())
                .isEqualTo(fixedCandidates.get(fixedCandidates.size() - 1).id().toString());
        assertThat(summary.getValue().path("activeConstraints").size()).isEqualTo(1);
        assertThat(summary.getValue().path("text").asText()).contains("不改日期");
        // 单独记账：即使 usage 缺失也按估算记账
        verify(repository).recordSummaryUsage(eq(run), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void casConflictDiscardsSummaryWithoutRetry() {
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(turn("摘要内容"));
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(false);

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
        verify(repository).recordSummaryUsage(any(), anyString(), any(), any(), anyBoolean(), any());
        // CAS 冲突后不再重试第二次模型调用
        verify(modelExecutor, never()).callModel(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void skipsWhenRemainingBudgetCannotFitSummaryRequest() {
        stubState(v2State(5, 1, null));

        summarizer.maybeSummarize(run(), composition(candidates()), 10);

        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
        verify(repository, never()).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void skipsWhenExistingSummaryAlreadyCoversLatestUnpickedMessage() {
        List<AgentMessageView> fixedCandidates = candidates();
        String throughId = fixedCandidates.get(fixedCandidates.size() - 1).id().toString();
        stubState(v2State(5, 1, throughId));

        summarizer.maybeSummarize(run(), composition(fixedCandidates), 10_000);

        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
    }

    @Test
    void modelFailureNeverBreaksMainTurn() {
        stubState(v2State(5, 1, null));
        when(modelExecutor.callModelWithoutTools(any(), any()))
                .thenThrow(new RuntimeException("provider exploded"));

        summarizer.maybeSummarize(run(), composition(candidates()), 10_000);

        verify(repository, never()).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
    }

    private void stubState(JsonNode state) {
        when(repository.workingState(any(), any())).thenReturn(state);
    }

    private JsonNode v2State(int stateRevision, int goalRevision, String summaryThrough) {
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
