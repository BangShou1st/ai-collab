package com.shitulelv.aicollab.retrieval;

import java.util.Map;

/** Test-only fail-closed authorization; no provider calls or secret values here. */
final class P10RealGenerationGate {
    private int attempts;
    private final boolean real;

    P10RealGenerationGate(Map<String, String> env) {
        real = "true".equals(env.get("AI_REAL_CHAT_ANSWER_QUALITY"));
        if (real && !("P10-REAL-GENERATION-01".equals(env.get("P10_APPROVAL"))
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

    synchronized void reserve() {
        if (!real || attempts >= 8) throw new IllegalStateException("P10 request denied by budget/authorization");
        attempts++;
    }

    int attempts() { return attempts; }
}
