package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B01: local timeout does not establish that the remote effect failed. */
class AgentRemoteOutcomeTest {
    @Test
    void canceledFutureDoesNotStopAnInterruptIgnoringRemoteEffect() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new AgentToolScheduler();
        var ctx = new com.shitulelv.aicollab.agent.domain.tool.AgentToolContext(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "OWNER", false, 0);
        try {
            var ticket = scheduler.submit(ctx, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10), () -> {
                entered.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                effects.incrementAndGet(); exited.countDown(); return true;
            });
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            ticket.cancel(true);
            assertThat(ticket.isCancelled()).isTrue();
            assertThat(scheduler.snapshot().active()).isEqualTo(1);
            release.countDown();
            assertThat(exited.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(effects).hasValue(1);
        } finally { release.countDown(); scheduler.close(); }
    }

    @Test
    void responseLostAfterDispatchMustNotBecomeRetryableFailure() {
        var json = new ObjectMapper();
        var repository = mock(AgentRepository.class);
        var run = mock(AgentRunView.class);
        var call = new ModelToolCall("lost", "remote_effect", json.createObjectNode());
        when(repository.dispatchedUnsafe(run, call)).thenReturn(true);
        when(repository.recordToolResult(any(), anyString(), any(), any(), anyBoolean())).thenReturn(run);
        var executor = new AgentToolCallExecutor(repository, null, null, null, null, null,
                new AgentToolResultSanitizer(json), json, mock(AgentEventService.class), null);
        ReflectionTestUtils.invokeMethod(executor, "recordToolTimeout", run, call);
        var result = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).recordToolResult(eq(run), eq(call.name()), any(), result.capture(), eq(true));
        assertThat(result.getValue().path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.getValue().path("retryable").asBoolean()).isFalse();
    }
}
