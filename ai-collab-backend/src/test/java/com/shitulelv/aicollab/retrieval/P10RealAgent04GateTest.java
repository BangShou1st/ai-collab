package com.shitulelv.aicollab.retrieval;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P10RealAgent04GateTest {
    @Test
    void defaultModeDeniesScenarioReservations() {
        var gate = new P10RealAgent04Gate(Map.of());

        assertThat(gate.real()).isFalse();
        assertThat(gate.model()).isEqualTo(P10RealAgent04Gate.APPROVED_MODEL);
        assertThatThrownBy(gate::reserveScenario).isInstanceOf(IllegalStateException.class);
        assertThat(gate.scenarios()).isZero();
    }

    @Test
    void realModeRequiresApprovedFreeRouteAndEnforcesScenarioBudget() {
        var gate = new P10RealAgent04Gate(approvedEnvironment());

        assertThat(gate.real()).isTrue();
        assertThat(gate.model()).isEqualTo(P10RealAgent04Gate.APPROVED_MODEL);
        assertThat(gate.approvedRouteSha256()).isEqualTo("a".repeat(64));
        for (int i = 0; i < P10RealAgent04Gate.MAX_SCENARIOS; i++) gate.reserveScenario();

        assertThatThrownBy(gate::reserveScenario).isInstanceOf(IllegalStateException.class);
        assertThat(gate.scenarios()).isEqualTo(P10RealAgent04Gate.MAX_SCENARIOS);
    }

    @Test
    void missingApprovalFieldsAndNonFreeRoutesFailClosed() {
        Map<String, String> approved = approvedEnvironment();
        for (String required : new String[]{"P10_APPROVAL", "P10_AGENT_MAX_SCENARIOS",
                "P10_AGENT_MAX_MODEL_CALLS_PER_SCENARIO", "P10_AGENT_APPROVED_ROUTE_SHA256",
                "P10_COST_AUTHORIZATION"}) {
            var missing = new HashMap<>(approved);
            missing.remove(required);
            assertThatThrownBy(() -> new P10RealAgent04Gate(missing))
                    .as("missing %s", required)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        var paidRoute = new HashMap<>(approved);
        paidRoute.put("P10_AGENT_APPROVED_MODEL", "openai/example-paid-model");
        assertThatThrownBy(() -> new P10RealAgent04Gate(paidRoute))
                .isInstanceOf(IllegalArgumentException.class);

        var malformedHash = new HashMap<>(approved);
        malformedHash.put("P10_AGENT_APPROVED_ROUTE_SHA256", "not-a-sha256");
        assertThatThrownBy(() -> new P10RealAgent04Gate(malformedHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> approvedEnvironment() {
        return Map.of(
                "P10_REAL_AGENT_04", "true",
                "P10_REAL_BATCH_ID", "p10-real-agent-04",
                "P10_APPROVAL", "P10-REAL-AGENT-04",
                "P10_AGENT_APPROVED_MODEL", P10RealAgent04Gate.APPROVED_MODEL,
                "P10_AGENT_APPROVED_ROUTE_SHA256", "a".repeat(64),
                "P10_AGENT_MAX_SCENARIOS", String.valueOf(P10RealAgent04Gate.MAX_SCENARIOS),
                "P10_AGENT_MAX_MODEL_CALLS_PER_SCENARIO", String.valueOf(P10RealAgent04Gate.MAX_MODEL_CALLS_PER_SCENARIO),
                "P10_COST_AUTHORIZATION", "FREE_ONLY_CONFIRMED"
        );
    }
}
