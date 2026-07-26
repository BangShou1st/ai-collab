package com.shitulelv.aicollab.knowledge.application.view;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record KnowledgeMessageView(
        UUID id,
        String role,
        String content,
        boolean insufficientEvidence,
        String model,
        List<KnowledgeCitationView> citations,
        OffsetDateTime createdAt) {
}
