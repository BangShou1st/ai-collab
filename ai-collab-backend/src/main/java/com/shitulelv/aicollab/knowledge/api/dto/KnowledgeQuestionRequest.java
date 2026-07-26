package com.shitulelv.aicollab.knowledge.api.dto;

import java.util.List;
import java.util.UUID;

public record KnowledgeQuestionRequest(String question, List<UUID> documentIds) {
}
