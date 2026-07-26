package com.shitulelv.aicollab.knowledge.application.view;

import java.util.List;

public record KnowledgeAnswerView(
        String answer,
        boolean insufficientEvidence,
        String model,
        List<KnowledgeCitationView> citations) {
}
