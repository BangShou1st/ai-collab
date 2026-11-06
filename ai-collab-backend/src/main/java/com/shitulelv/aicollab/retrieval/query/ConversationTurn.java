package com.shitulelv.aicollab.retrieval.query;

import java.time.OffsetDateTime;

/**
 * Represents a complete structured conversation turn consisting of a user question
 * and the corresponding assistant response.
 *
 * Structuring into complete turns avoids arbitrarily severing conversation exchanges
 * in the middle of a question-answer pair.
 */
public record ConversationTurn(
        int turnIndex,
        String userQuestion,
        String assistantAnswer,
        OffsetDateTime timestamp,
        boolean insufficientEvidence) {

    public boolean hasAssistantAnswer() {
        return assistantAnswer != null && !assistantAnswer.isBlank();
    }
}
