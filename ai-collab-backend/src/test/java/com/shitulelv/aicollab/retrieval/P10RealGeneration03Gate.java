package com.shitulelv.aicollab.retrieval;

import java.util.Map;

/**
 * P10-REAL-GENERATION-03 专属 fail-closed 授权门禁；独立于 02 批次门禁，不改变历史批次的
 * 预算语义。真实模式要求显式授权、固定模型、17 次请求预算、免费边界与限速确认。
 * 此处不发生任何 Provider 调用，也不持有密钥值。
 */
final class P10RealGeneration03Gate {
    static final int MAX_REQUESTS = 17;
    private int attempts;
    private final boolean real;
    private final String batchId;

    P10RealGeneration03Gate(Map<String, String> env) {
        real = "true".equals(env.get("P10_REAL_GENERATION_03"));
        batchId = env.getOrDefault("P10_REAL_BATCH_ID", "p10-real-generation-03");
        if (real && !(batchId.matches("p10-real-generation-[a-z0-9-]+")
                && !batchId.equals("p10-real-generation-01")
                && !batchId.equals("p10-real-generation-02")
                && batchId.toUpperCase(java.util.Locale.ROOT).equals(env.get("P10_APPROVAL"))
                && String.valueOf(MAX_REQUESTS).equals(env.get("P10_MAX_REQUESTS"))
                && "1024".equals(env.get("P10_MAX_OUTPUT_TOKENS"))
                && "gemini-3.5-flash-lite".equals(env.get("P10_APPROVED_MODEL"))
                && env.getOrDefault("P10_APPROVED_ROUTE_SHA256", "").matches("[a-f0-9]{64}")
                && "FREE_ONLY_CONFIRMED".equals(env.get("P10_COST_AUTHORIZATION"))
                && Integer.parseInt(env.getOrDefault("P10_INTERVAL_SECONDS", "0")) >= 15)) {
            throw new IllegalArgumentException("P10 real generation 03 requires explicit approval, model, budget, free-tier and rate confirmation");
        }
    }

    boolean real() { return real; }
    String batchId() { return batchId; }

    synchronized void reserve() {
        if (!real || attempts >= MAX_REQUESTS) throw new IllegalStateException("P10 request denied by budget/authorization");
        attempts++;
    }

    int attempts() { return attempts; }
}
