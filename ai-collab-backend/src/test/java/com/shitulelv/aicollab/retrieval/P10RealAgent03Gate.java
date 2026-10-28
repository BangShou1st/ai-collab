package com.shitulelv.aicollab.retrieval;

import java.util.Map;

/**
 * P10-REAL-GENERATION-03 Agent 侧 fail-closed 授权门禁。真实模式要求显式授权、
 * 固定免费模型、获批路由哈希、20 场景预算、每场景模型调用上限与免费边界确认。
 * 不发生任何 Provider 调用，不持有密钥值。
 */
final class P10RealAgent03Gate {
    static final int MAX_SCENARIOS = 20;
    static final int MAX_MODEL_CALLS_PER_SCENARIO = 12;
    static final String APPROVED_MODEL = "nvidia/nemotron-3.5-lightning:free";
    private int scenarioCount;
    private final boolean real;
    private final String batchId;
    private final String model;
    private final String approvedRouteSha256;

    P10RealAgent03Gate(Map<String, String> env) {
        real = "true".equals(env.get("P10_REAL_AGENT_03"));
        batchId = env.getOrDefault("P10_REAL_BATCH_ID", "p10-real-agent-03");
        model = env.getOrDefault("P10_AGENT_APPROVED_MODEL", APPROVED_MODEL);
        approvedRouteSha256 = env.getOrDefault("P10_AGENT_APPROVED_ROUTE_SHA256", "");
        if (real && !(batchId.matches("p10-real-agent-[a-z0-9-]+")
                && !batchId.equals("p10-real-agent-01")
                && batchId.toUpperCase(java.util.Locale.ROOT).equals(env.get("P10_APPROVAL"))
                && String.valueOf(MAX_SCENARIOS).equals(env.get("P10_AGENT_MAX_SCENARIOS"))
                && String.valueOf(MAX_MODEL_CALLS_PER_SCENARIO).equals(env.get("P10_AGENT_MAX_MODEL_CALLS_PER_SCENARIO"))
                && APPROVED_MODEL.equals(model)
                && approvedRouteSha256.matches("[a-f0-9]{64}")
                && "FREE_ONLY_CONFIRMED".equals(env.get("P10_COST_AUTHORIZATION")))) {
            throw new IllegalArgumentException("P10 real agent 03 requires explicit approval, model, scenario budget and free-tier confirmation");
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
