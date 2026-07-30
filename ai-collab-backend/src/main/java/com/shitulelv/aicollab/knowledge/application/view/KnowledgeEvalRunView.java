package com.shitulelv.aicollab.knowledge.application.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeEvalRunView(
        UUID id,
        String status,
        int totalQuestions,
        BigDecimal recallAt3,
        BigDecimal recallAt5,
        BigDecimal mrr,
        BigDecimal avgSimilarity,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}
