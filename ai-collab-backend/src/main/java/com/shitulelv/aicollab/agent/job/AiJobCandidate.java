package com.shitulelv.aicollab.agent.job;

import java.math.BigDecimal;

/**
 * Memory 候选（W5 冻结 §3.5）。本波只用于 results 请求的幂等键与审计载荷，
 * <b>不进入</b> {@code agent_memory}，{@code review_status} 恒为 PENDING_REVIEW。
 */
public record AiJobCandidate(
        String kind,
        String title,
        String content,
        BigDecimal confidence,
        String sourceRef,
        String dedupeKey) {
}
