package com.shitulelv.aicollab.agent.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicFixedDelayTriggerTest {

    @Test
    void firstExecutionIsImmediate() {
        DynamicFixedDelayTrigger trigger = new DynamicFixedDelayTrigger(() -> 500L);

        Instant next = trigger.nextExecution(new StubTriggerContext(null));

        assertThat(next).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void subsequentExecutionIsLastCompletionPlusCurrentDelay() {
        DynamicFixedDelayTrigger trigger = new DynamicFixedDelayTrigger(() -> 1500L);
        Instant lastCompletion = Instant.now().minusSeconds(10);

        Instant next = trigger.nextExecution(new StubTriggerContext(lastCompletion));

        assertThat(next).isEqualTo(lastCompletion.plusMillis(1500L));
    }

    @Test
    void nonPositiveDelayIsGuardedToAtLeastOneMillisecond() {
        DynamicFixedDelayTrigger trigger = new DynamicFixedDelayTrigger(() -> 0L);
        Instant lastCompletion = Instant.now();

        Instant next = trigger.nextExecution(new StubTriggerContext(lastCompletion));

        assertThat(next).isEqualTo(lastCompletion.plusMillis(1L));
    }

    private record StubTriggerContext(Instant lastCompletion)
            implements org.springframework.scheduling.TriggerContext {
        @Override
        public Instant lastScheduledExecution() {
            return lastCompletion;
        }

        @Override
        public Instant lastActualExecution() {
            return lastCompletion;
        }

        @Override
        public Instant lastCompletion() {
            return lastCompletion;
        }
    }
}
