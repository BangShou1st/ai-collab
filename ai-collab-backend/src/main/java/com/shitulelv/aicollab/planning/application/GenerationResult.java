package com.shitulelv.aicollab.planning.application;

/**
 * P2-1: Carries model output content together with observability metrics
 * so the orchestrator can persist them to the attempt record.
 */
public record GenerationResult(
        String content,
        String provider,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens) {}
