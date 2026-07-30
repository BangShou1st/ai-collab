package com.shitulelv.aicollab.knowledge.application.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeStreamEvent(
        String type,
        String text,
        List<KnowledgeCitationView> citations,
        String messageId,
        String code,
        String message) {

    public static KnowledgeStreamEvent token(String text) {
        return new KnowledgeStreamEvent("token", text, null, null, null, null);
    }

    public static KnowledgeStreamEvent citations(List<KnowledgeCitationView> citations) {
        return new KnowledgeStreamEvent("citations", null, citations, null, null, null);
    }

    public static KnowledgeStreamEvent done(String messageId) {
        return new KnowledgeStreamEvent("done", null, null, messageId, null, null);
    }

    public static KnowledgeStreamEvent error(String code, String message) {
        return new KnowledgeStreamEvent("error", null, null, null, code, message);
    }
}
