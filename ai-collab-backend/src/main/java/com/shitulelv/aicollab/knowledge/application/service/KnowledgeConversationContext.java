package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.retrieval.query.ConversationMessage;
import com.shitulelv.aicollab.retrieval.query.QueryUnderstandingResult;
import com.shitulelv.aicollab.retrieval.query.QueryUnderstandingService;

import java.util.List;

/**
 * Multi-turn context adapter that resolves references and追问指代 using QueryUnderstandingService.
 * Enforces that history is only used for coreference resolution and that past assistant answers
 * cannot be used as evidence for the current turn.
 */
public record KnowledgeConversationContext(String prompt, String retrievalQuery) {

    private static final QueryUnderstandingService UNDERSTANDING_SERVICE = new QueryUnderstandingService();

    public static KnowledgeConversationContext from(List<KnowledgeMessageEntity> recent, String question) {
        List<ConversationMessage> convMessages = recent == null ? List.of() : recent.stream()
                .map(m -> new ConversationMessage(
                        m.getRole(),
                        m.getContent(),
                        m.getCreatedAt(),
                        m.isInsufficientEvidence()
                ))
                .toList();

        QueryUnderstandingResult result = UNDERSTANDING_SERVICE.understand(convMessages, question);
        return new KnowledgeConversationContext(result.contextPrompt(), result.rewrittenQuery());
    }
}

