package com.shitulelv.aicollab.retrieval;

import java.util.Locale;
import java.util.Map;

/**
 * P10-REAL-GENERATION-04 Agent 截断故障归因专项门禁。
 * 针对 AG06、AG15、AG19 截断场景及对照场景进行有限真实复测。
 * 必须显式授权、限定免费模型、限定场景数与调用预算、确认免费配额。
 */
final class P10RealAgent04Gate {
    static final int MAX_SCENARIOS = 4;
    static final int MAX_MODEL_CALLS_PER_SCENARIO = 12;
    static final String APPROVED_MODEL = "nvidia/nemotron-3.5-lightning:free";
    private int scenarioCount;
    private final boolean real;
    private final String batchId;
    private final String model;
    private final String approvedRouteSha256;

    P10RealAgent04Gate(Map<String, String> env) {
        real = "true".equals(env.get("P10_REAL_AGENT_04"));
        batchId = env.getOrDefault("P10_REAL_BATCH_ID", "p10-real-agent-04");
        model = env.getOrDefault("P10_AGENT_APPROVED_MODEL", APPROVED_MODEL);
        approvedRouteSha256 = env.getOrDefault("P10_AGENT_APPROVED_ROUTE_SHA256", "");
        if (real && !(batchId.matches("p10-real-agent-[a-z0-9-]+")
                && batchId.toUpperCase(Locale.ROOT).equals(env.get("P10_APPROVAL"))
                && String.valueOf(MAX_SCENARIOS).equals(env.get("P10_AGENT_MAX_SCENARIOS"))
                && String.valueOf(MAX_MODEL_CALLS_PER_SCENARIO).equals(env.get("P10_AGENT_MAX_MODEL_CALLS_PER_SCENARIO"))
                && APPROVED_MODEL.equals(model)
                && approvedRouteSha256.matches("[a-f0-9]{64}")
                && "FREE_ONLY_CONFIRMED".equals(env.get("P10_COST_AUTHORIZATION")))) {
            throw new IllegalArgumentException("P10 real agent 04 requires explicit approval, model, scenario budget and free-tier confirmation");
        }
    }

    boolean real() { return real; }
    String batchId() { return batchId; }
    String model() { return model; }
    String approvedRouteSha256() { return approvedRouteSha256; }

    synchronized void reserveScenario() {
        if (!real || scenarioCount >= MAX_SCENARIOS)
            throw new IllegalStateException("P10 agent scenario denied by budget/authorization");
        scenarioCount++;
    }

    int scenarios() { return scenarioCount; }
}
