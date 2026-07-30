package com.shitulelv.aicollab.knowledge.application.view;

import java.util.List;
import java.util.UUID;

public record KnowledgeAnswerView(
        UUID messageId,
        String answer,
        boolean insufficientEvidence,
        String model,
        List<KnowledgeCitationView> citations) {
}
