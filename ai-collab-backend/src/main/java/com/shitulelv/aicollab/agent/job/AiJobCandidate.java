package com.shitulelv.aicollab.agent.job;

import java.math.BigDecimal;

/**
 * Memory 候选（W5 冻结 §3.5）。先进入 {@code ai_job_result}（PENDING_REVIEW），
 * 只有项目管理员显式 approve 后才由审核服务转换为 {@code agent_memory}。
 */
public record AiJobCandidate(
        String kind,
        String title,
        String content,
        BigDecimal confidence,
        String sourceRef,
        String dedupeKey) {
}
