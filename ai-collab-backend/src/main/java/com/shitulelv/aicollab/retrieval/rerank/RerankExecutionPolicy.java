package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;

/**
 * Rerank 产品边界：只有 8 候选 / 5s 的运行档位允许进入用户请求关键路径。
 *
 * <p>20/50 候选只能走后台 shadow、离线能力测试或尚未实现的 async 路径。
 * 当前没有 async 执行器，调用方只能明确返回 {@link #ASYNC_NOT_IMPLEMENTED}，
 * 不得把大候选档位伪装成 inline。</p>
 *
 * <p>2026-11-15 起 inline deadline 由 3s 调整为 5s：4 vCPU CPU-only reranker 在
 * 6 候选单请求约 1.8s、两路并发约 3.8s，3s 会在并发时稳定误杀；5s 仍保留
 * 超时回退的硬边界，不把 shadow/offline 的超大预算带入用户路径。</p>
 */
public final class RerankExecutionPolicy {

    public static final int INLINE_MAX_CANDIDATES = 8;
    public static final int INLINE_MAX_DEADLINE_MS = 5000;
    public static final int OFFLINE_MAX_CANDIDATES = 50;
    public static final String INLINE_CANDIDATE_LIMIT_REASON = "inline_candidate_limit";
    public static final String ASYNC_NOT_IMPLEMENTED = "ASYNC_NOT_IMPLEMENTED";

    private RerankExecutionPolicy() {
    }

    public enum Lane {
        INLINE,
        SHADOW,
        OFFLINE,
        ASYNC
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
