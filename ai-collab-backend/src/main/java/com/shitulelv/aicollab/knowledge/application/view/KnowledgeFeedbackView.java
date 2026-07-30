package com.shitulelv.aicollab.knowledge.application.view;

public record KnowledgeFeedbackView(
        Boolean myFeedback,
        int helpfulCount,
        int unhelpfulCount) {
}
