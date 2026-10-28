package com.shitulelv.aicollab.retrieval;

import java.util.Map;

/** Test-only fail-closed authorization; no provider calls or secret values here. */
final class P10RealGenerationGate {
    private int attempts;
    private final boolean real;
    private final String batchId;

    P10RealGenerationGate(Map<String, String> env) {
        real = "true".equals(env.get("AI_REAL_CHAT_ANSWER_QUALITY"));
        batchId = env.getOrDefault("P10_REAL_BATCH_ID", "p10-real-generation-01");
        if (real && !(batchId.matches("p10-real-generation-[a-z0-9-]+")
                && !batchId.equals("p10-real-generation-01")
                && batchId.toUpperCase(java.util.Locale.ROOT).equals(env.get("P10_APPROVAL"))
                && "8".equals(env.get("P10_MAX_REQUESTS"))
                && "1024".equals(env.get("P10_MAX_OUTPUT_TOKENS"))
                && "gemini-3.5-flash-lite".equals(env.get("P10_APPROVED_MODEL"))
                && env.getOrDefault("P10_APPROVED_ROUTE_SHA256", "").matches("[a-f0-9]{64}")
                && "FREE_ONLY_CONFIRMED".equals(env.get("P10_COST_AUTHORIZATION"))
                && Integer.parseInt(env.getOrDefault("P10_INTERVAL_SECONDS", "0")) >= 15)) {
            throw new IllegalArgumentException("P10 real generation requires explicit approval, model, budget, free-tier and rate confirmation");
        }
    }

    boolean real() { return real; }
    String batchId() { return batchId; }

    synchronized void reserve() {
        if (!real || attempts >= 8) throw new IllegalStateException("P10 request denied by budget/authorization");
        attempts++;
    }

    int attempts() { return attempts; }
}
