package com.shitulelv.aicollab.knowledge.domain.model;

import java.util.List;

public record ValidatedKnowledgeAnswer(
        String answer,
        boolean insufficientEvidence,
        List<KnowledgeSource> citedSources,
        int invalidCitationCount,
        boolean invalidOutput,
        List<String> unsupportedClaims) {

    public ValidatedKnowledgeAnswer(
            String answer,
            boolean insufficientEvidence,
            List<KnowledgeSource> citedSources,
            int invalidCitationCount,
            boolean invalidOutput) {
        this(answer, insufficientEvidence, citedSources, invalidCitationCount, invalidOutput, List.of());
    }

    public ValidatedKnowledgeAnswer {
        citedSources = citedSources != null ? List.copyOf(citedSources) : List.of();
        unsupportedClaims = unsupportedClaims != null ? List.copyOf(unsupportedClaims) : List.of();
    }
}
