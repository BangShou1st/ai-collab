package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AgentWorkerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void finalDecisionCompletesRunWithStructuredAnswer() {
        Fixture fixture = fixture(runWithUsage(0, 0));
        ChatModelGateway model = fixed("""
                {"action":"final","answer":"进度正常","citations":[],"inferences":[]}
                """, 80, 20);
        AgentWorker worker = fixture.worker(model);

        AgentWorkerOutcome outcome = worker.process(fixture.claimed());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("进度正常");
        assertThat(outcome.toolName()).isNull();
    }

    @Test
    void exhaustedBudgetStopsBeforeModelCall() {
        Fixture fixture = fixture(runWithUsage(12, 0));
        AtomicInteger calls = new AtomicInteger();
        ChatModelGateway model = new ChatModelGateway() {
            @Override public ChatCompletionResult complete(ChatCompletionCommand command) {
                calls.incrementAndGet();
                throw new AssertionError("预算耗尽时不能调用模型");
            }
            @Override public void completeStream(
                    ChatCompletionCommand command, Consumer<String> onToken,
                    Consumer<ChatCompletionResult> onDone, Consumer<Exception> onError) {
                throw new UnsupportedOperationException();
            }
        };

        AgentWorkerOutcome outcome = fixture.worker(model).process(fixture.claimed());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unknownToolFailsOnceAndAccountsModelDecision() {
        Fixture fixture = fixture(runWithUsage(0, 0));
        AgentWorkerOutcome outcome = fixture.worker(fixed("""
                {"action":"call_tool","tool":"not_registered","arguments":{},"reason":"test"}
                """, 80, 20)).process(fixture.claimed());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        verify(fixture.repository()).recordDecisionFailure(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("AGENT_TOOL_EXECUTION_FAILED"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void responseThatCrossesRemainingTokenBudgetTerminatesWithoutPersistingDecision() {
        Fixture fixture = fixture(runWithUsage(0, 0));
        AgentWorkerOutcome outcome = fixture.worker(fixed("""
                {"action":"final","answer":"too large","citations":[],"inferences":[]}
                """, 50_001, 20)).process(fixture.claimed());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(fixture.repository()).recordBudgetExceeded(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Fixture fixture(AgentRunView run) {
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(java.util.Optional.of(run));
        when(repository.listMessages(run.projectId(), run.sessionId(), 100)).thenReturn(List.of());
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(List.of());
        ClaimedAgentRun claimed = new ClaimedAgentRun(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, run.goal(),
                AgentRunStatus.QUEUED, false, false, run.version());
        return new Fixture(repository, claimed);
    }

    private AgentRunView runWithUsage(int steps, int tools) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                12, 8, 3, 50_000, 20_000,
                steps, tools, 0, 0, 0, false,
                false, false, 0, null, 1, now, now);
    }

    private ChatModelGateway fixed(String content, int promptTokens, int completionTokens) {
        return new ChatModelGateway() {
            @Override public ChatCompletionResult complete(ChatCompletionCommand command) {
                return new ChatCompletionResult(
                        content, "test", "test-model", promptTokens, completionTokens, 12);
            }
            @Override public void completeStream(
                    ChatCompletionCommand command, Consumer<String> onToken,
                    Consumer<ChatCompletionResult> onDone, Consumer<Exception> onError) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record Fixture(AgentRepository repository, ClaimedAgentRun claimed) {
        AgentWorker worker(ChatModelGateway model) {
            return new AgentWorker(
                    repository, model, new AgentDecisionParser(new ObjectMapper()),
                    new AgentPromptFactory(), new AgentToolRegistry(List.of()),
                    new ObjectMapper());
        }
    }
}
