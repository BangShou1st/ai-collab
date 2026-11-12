package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiJobDispatchSchedulerTest {

    @Test
    void dispatchesEveryQueuedJobAndKeepsGoingAfterOneFailure() {
        AiJobRepository repository = mock(AiJobRepository.class);
        AiJobDispatcher dispatcher = mock(AiJobDispatcher.class);
        AiJobRecord first = job();
        AiJobRecord second = job();
        when(repository.findQueued(any(), eq(20))).thenReturn(List.of(first, second));
        doThrow(new AiJobException(AiJobException.Kind.INVALID_STATE, "not queued"))
                .when(dispatcher).dispatch(first.id());

        new AiJobDispatchScheduler(repository, dispatcher).dispatchQueued();

        verify(dispatcher).dispatch(first.id());
        verify(dispatcher).dispatch(second.id());
    }

    private static AiJobRecord job() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AiJobRecord(UUID.randomUUID(), UUID.randomUUID(), "MEMORY_EXTRACTION",
                AiJobStatus.QUEUED, 1, "{}", 3, 0, 0, null, null, null, null,
                null, null, now, now, 0);
    }
}
