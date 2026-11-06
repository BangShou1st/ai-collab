package com.shitulelv.aicollab.retrieval.query;

/**
 * Categorization of user query intent in multi-turn conversation.
 */
public enum QueryIntentType {
    /**
     * Independent, self-contained question with clear subject and predicates.
     * Does not require previous conversation history for retrieval.
     */
    STANDALONE,

    /**
     * Follow-up, elliptical, or referential question containing pronouns or demonstratives,
     * needing contextual resolution with previous turns.
     */
    FOLLOW_UP,

    /**
     * Explicit topic switch or pivot away from previous subject.
     * Must not inherit previous topic context.
     */
    TOPIC_SWITCH
}
