package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiJobDispatcherTest {

    @Test
    void readsWorkerStatusAndRejectsMalformedBodies() {
        assertThat(AiJobDispatcher.workerStatus(
                "{\"status\":\"FAILED_RETRYABLE\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(AiJobDispatcher.workerStatus(
                "{\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("SUCCEEDED");
        assertThat(AiJobDispatcher.workerStatus("not-json".getBytes(StandardCharsets.UTF_8)))
                .isNull();
        assertThat(AiJobDispatcher.workerStatus(new byte[0])).isNull();
        assertThat(AiJobDispatcher.DEADLINE_MS).isEqualTo(30_000);
    }
}
