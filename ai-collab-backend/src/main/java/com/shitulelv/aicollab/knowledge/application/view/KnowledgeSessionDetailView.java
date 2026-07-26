package com.shitulelv.aicollab.knowledge.application.view;

import java.util.List;

public record KnowledgeSessionDetailView(
        KnowledgeSessionView session,
        List<KnowledgeMessageView> messages) {
}
