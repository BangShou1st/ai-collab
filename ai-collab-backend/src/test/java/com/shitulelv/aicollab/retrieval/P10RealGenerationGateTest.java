package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class P10RealGenerationGateTest {
    @Test
    void defaultsDenyAllRequestsAndIncompleteApprovalFailsClosed() {
        var gate = new P10RealGenerationGate(Map.of());
        assertThat(gate.real()).isFalse();
        assertThatThrownBy(gate::reserve).isInstanceOf(IllegalStateException.class);
        assertThat(gate.attempts()).isZero();
        assertThatThrownBy(() -> new P10RealGenerationGate(Map.of("AI_REAL_CHAT_ANSWER_QUALITY", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachAuthorizationFieldIsRequiredAndFailedAttemptsConsumeBudget() {
        Map<String, String> env = Map.of("AI_REAL_CHAT_ANSWER_QUALITY", "true",
                "P10_APPROVAL", "P10-REAL-GENERATION-01", "P10_MAX_REQUESTS", "8",
                "P10_MAX_OUTPUT_TOKENS", "1024", "P10_APPROVED_MODEL", "gemini-3.5-flash-lite",
                "P10_COST_AUTHORIZATION", "FREE_ONLY_CONFIRMED", "P10_INTERVAL_SECONDS", "15",
                "P10_APPROVED_ROUTE_SHA256", "a".repeat(64));
        for (String field : env.keySet()) {
            if (field.equals("AI_REAL_CHAT_ANSWER_QUALITY")) continue;
            var missing = new HashMap<>(env);
            missing.remove(field);
            assertThatThrownBy(() -> new P10RealGenerationGate(missing)).isInstanceOf(IllegalArgumentException.class);
        }
        var gate = new P10RealGenerationGate(env);
        for (int i = 0; i < 8; i++) gate.reserve();
        assertThatThrownBy(gate::reserve).isInstanceOf(IllegalStateException.class);
        assertThat(gate.attempts()).isEqualTo(8);
    }
}
