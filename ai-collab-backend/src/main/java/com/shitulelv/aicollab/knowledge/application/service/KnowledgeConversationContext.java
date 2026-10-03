package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptText;
import java.util.*;

/** History resolves references; only fresh sources support the answer. */
public record KnowledgeConversationContext(String prompt, String retrievalQuery) {
    public static KnowledgeConversationContext from(List<KnowledgeMessageEntity> recent, String question) {
        List<KnowledgeMessageEntity> messages = recent == null ? List.of() : new ArrayList<>(recent);
        messages.sort(Comparator.comparing(KnowledgeMessageEntity::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        StringBuilder history = new StringBuilder();
        String previousQuestion = "";
        for (KnowledgeMessageEntity message : messages.subList(Math.max(0, messages.size() - 6), messages.size())) {
            if (message.getContent() == null) continue;
            if ("USER".equals(message.getRole())) previousQuestion = message.getContent();
            if (message.isInsufficientEvidence()) continue;
            String text = message.getContent();
            if (text.length() > 1000) text = text.substring(0, 1000);
            if (history.length() + text.length() > 4000) continue;
            history.append(message.getRole()).append(": ").append(KnowledgePromptText.escapeXmlText(text)).append('\n');
        }
        String query = question;
        if (question.matches("(?s).*(它|他们|这个|那个|这些|上述|刚才|继续|具体|为什么|如何|再).*") && !previousQuestion.isBlank()) {
            int budget = Math.max(0, 1990 - question.codePointCount(0, question.length()));
            int length = Math.min(budget, previousQuestion.codePointCount(0, previousQuestion.length()));
            if (length > 0) query = previousQuestion.substring(0, previousQuestion.offsetByCodePoints(0, length)) + "\n" + question;
        }
        return new KnowledgeConversationContext("<CONVERSATION_CONTEXT>\n" + history + "</CONVERSATION_CONTEXT>\n"
                + "历史仅用于理解追问，历史回答及其引用编号不能作为本轮证据；仅使用本轮 SOURCES，明确标注推断。\n", query);
    }
}
