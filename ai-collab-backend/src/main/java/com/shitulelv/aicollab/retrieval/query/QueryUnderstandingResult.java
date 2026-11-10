package com.shitulelv.aicollab.retrieval.query;

import java.util.List;

/**
 * Result of query understanding, containing the verbatim original user question,
 * the contextualized rewritten query for retrieval, detected intent,
 * the prompt for the conversation context, and the managed turns.
 */
public record QueryUnderstandingResult(
        String originalQuestion,
        String rewrittenQuery,
        QueryIntentType intentType,
        String contextPrompt,
        List<ConversationTurn> managedTurns) {

    public QueryUnderstandingResult {
        originalQuestion = originalQuestion != null ? originalQuestion : "";
        rewrittenQuery = rewrittenQuery != null ? rewrittenQuery : originalQuestion;
        intentType = intentType != null ? intentType : QueryIntentType.STANDALONE;
        contextPrompt = contextPrompt != null ? contextPrompt : "";
        managedTurns = managedTurns != null ? List.copyOf(managedTurns) : List.of();
    }
}
