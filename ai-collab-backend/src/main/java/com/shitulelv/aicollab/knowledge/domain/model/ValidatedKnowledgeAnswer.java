package com.shitulelv.aicollab.knowledge.domain.model;

import java.util.List;

public record ValidatedKnowledgeAnswer(
        String answer,
        boolean insufficientEvidence,
        List<KnowledgeSource> citedSources,
        int invalidCitationCount,
        boolean invalidOutput) {
}
