package com.shitulelv.aicollab.agent.application.view;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 待审核 Memory 候选的安全视图；正文只对项目管理员返回。 */
public record AgentMemoryCandidateView(
        UUID id,
        UUID jobId,
        int attemptNo,
        String kind,
        String title,
        String content,
        BigDecimal confidence,
        String sourceRef,
        String reviewStatus,
        OffsetDateTime createdAt) {
}
