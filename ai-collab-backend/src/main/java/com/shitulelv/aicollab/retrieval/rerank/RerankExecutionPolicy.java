package com.shitulelv.aicollab.retrieval.rerank;

import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;

/**
 * Rerank 产品边界：只有 8 候选 / 3s 的运行档位允许进入用户请求关键路径。
 *
 * <p>20/50 候选只能走后台 shadow、离线能力测试或尚未实现的 async 路径。
 * 当前没有 async 执行器，调用方只能明确返回 {@link #ASYNC_NOT_IMPLEMENTED}，
 * 不得把大候选档位伪装成 inline。</p>
 */
public final class RerankExecutionPolicy {

    public static final int INLINE_MAX_CANDIDATES = 8;
    public static final int INLINE_MAX_DEADLINE_MS = 3000;
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
