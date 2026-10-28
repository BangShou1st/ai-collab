package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P10RealGeneration03GateTest {
    @Test
    void defaultModeDeniesRequests() {
        var gate = new P10RealGeneration03Gate(Map.of());

        assertThat(gate.real()).isFalse();
        assertThatThrownBy(gate::reserve).isInstanceOf(IllegalStateException.class);
        assertThat(gate.attempts()).isZero();
    }

    @Test
    void realModeRequiresAllApprovalsAndEnforcesFixedRequestBudget() {
        var gate = new P10RealGeneration03Gate(approvedEnvironment());

        assertThat(gate.real()).isTrue();
        assertThat(gate.batchId()).isEqualTo("p10-real-generation-03");
        for (int i = 0; i < P10RealGeneration03Gate.MAX_REQUESTS; i++) gate.reserve();

        assertThatThrownBy(gate::reserve).isInstanceOf(IllegalStateException.class);
        assertThat(gate.attempts()).isEqualTo(P10RealGeneration03Gate.MAX_REQUESTS);
    }

    @Test
    void missingApprovalFieldsAndPaidModelsFailClosed() {
        Map<String, String> approved = approvedEnvironment();
        for (String required : new String[]{"P10_APPROVAL", "P10_MAX_REQUESTS", "P10_MAX_OUTPUT_TOKENS",
                "P10_APPROVED_MODEL", "P10_APPROVED_ROUTE_SHA256", "P10_COST_AUTHORIZATION",
                "P10_INTERVAL_SECONDS"}) {
            var missing = new HashMap<>(approved);
            missing.remove(required);
            assertThatThrownBy(() -> new P10RealGeneration03Gate(missing))
                    .as("missing %s", required)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        var oldBatch = new HashMap<>(approved);
        oldBatch.put("P10_REAL_BATCH_ID", "p10-real-generation-02");
        oldBatch.put("P10_APPROVAL", "P10-REAL-GENERATION-02");
        assertThatThrownBy(() -> new P10RealGeneration03Gate(oldBatch))
                .isInstanceOf(IllegalArgumentException.class);

        var paidModel = new HashMap<>(approved);
        paidModel.put("P10_APPROVED_MODEL", "openai/example-paid-model");
        assertThatThrownBy(() -> new P10RealGeneration03Gate(paidModel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> approvedEnvironment() {
        return Map.of(
                "P10_REAL_GENERATION_03", "true",
                "P10_REAL_BATCH_ID", "p10-real-generation-03",
                "P10_APPROVAL", "P10-REAL-GENERATION-03",
                "P10_MAX_REQUESTS", "17",
                "P10_MAX_OUTPUT_TOKENS", "1024",
                "P10_APPROVED_MODEL", "gemini-3.5-flash-lite",
                "P10_APPROVED_ROUTE_SHA256", "a".repeat(64),
                "P10_COST_AUTHORIZATION", "FREE_ONLY_CONFIRMED",
                "P10_INTERVAL_SECONDS", "15");
    }
}
