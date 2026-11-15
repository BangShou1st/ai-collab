package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RerankExecutionPolicyTest {

    @Test
    void inlineDeadlineAllowsFiveSecondsAndCapsLargerValues() {
        assertThat(RerankExecutionPolicy.inlineDeadlineMs(4500)).isEqualTo(4500);
        assertThat(RerankExecutionPolicy.inlineDeadlineMs(5000)).isEqualTo(5000);
        assertThat(RerankExecutionPolicy.inlineDeadlineMs(9000)).isEqualTo(5000);
    }

    @Test
    void inlineSettingsClampsDeadlineWithoutChangingCandidateBoundary() {
        RerankSettings settings = new RerankSettings(
                "llama-cpp", 9000, 6, 6, 64, "sigmoid", 6, "/health", "java-heuristic");

        RerankSettings inline = RerankExecutionPolicy.inlineSettings(settings);

        assertThat(inline.timeoutMs()).isEqualTo(5000);
        assertThat(inline.maxCandidates()).isEqualTo(6);
        assertThat(inline.topK()).isEqualTo(6);
    }
}
