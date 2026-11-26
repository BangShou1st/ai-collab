package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;

/**
 * Rerank inline 边界：进入用户请求关键路径的重排最多 8 个候选、deadline 不超过 5s。
 *
 * <p>候选数超过 {@link #INLINE_MAX_CANDIDATES} 时不做 inline 重排（见 {@link #inlineAllowed(int)}），
 * 不得把大候选档位伪装成 inline；配置的超时一律被夹到 {@link #INLINE_MAX_DEADLINE_MS} 以内。</p>
 *
 * <p>2026-11-15 起 inline deadline 由 3s 调整为 5s：4 vCPU CPU-only reranker 在
 * 6 候选单请求约 1.8s、两路并发约 3.8s，3s 会在并发时稳定误杀；5s 仍保留
 * 超时回退的硬边界。</p>
 */
public final class RerankExecutionPolicy {

    public static final int INLINE_MAX_CANDIDATES = 8;
    public static final int INLINE_MAX_DEADLINE_MS = 5000;
    public static final String INLINE_CANDIDATE_LIMIT_REASON = "inline_candidate_limit";

    private RerankExecutionPolicy() {
    }

    public static boolean inlineAllowed(int candidateCount) {
        return candidateCount <= INLINE_MAX_CANDIDATES;
    }

    public static int inlineDeadlineMs(int configuredDeadlineMs) {
        return Math.max(1, Math.min(configuredDeadlineMs, INLINE_MAX_DEADLINE_MS));
    }

    public static RerankSettings inlineSettings(RerankSettings settings) {
        int deadlineMs = inlineDeadlineMs(settings.timeoutMs());
        if (deadlineMs == settings.timeoutMs()) {
            return settings;
        }
        return new RerankSettings(settings.protocol(), deadlineMs, settings.maxCandidates(),
                settings.batchSize(), settings.maxLength(), settings.scoreType(), settings.topK(),
                settings.healthPath(), settings.fallback());
    }
}
