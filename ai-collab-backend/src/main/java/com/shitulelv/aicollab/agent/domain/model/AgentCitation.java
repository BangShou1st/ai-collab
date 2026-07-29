package com.shitulelv.aicollab.agent.domain.model;

import java.util.UUID;

public record AgentCitation(
        UUID documentId,
        UUID chunkId,
        String filename,
        String heading,
        Integer pageNumber,
        String quote,
        double similarity) {
}
