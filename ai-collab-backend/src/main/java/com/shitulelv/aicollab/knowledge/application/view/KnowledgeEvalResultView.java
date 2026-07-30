package com.shitulelv.aicollab.knowledge.application.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeEvalResultView(
        String question,
        List<String> expectedDocumentIds,
        List<String> retrievedDocumentIds,
        BigDecimal recallAt3,
        BigDecimal recallAt5,
        BigDecimal mrr) {
}
