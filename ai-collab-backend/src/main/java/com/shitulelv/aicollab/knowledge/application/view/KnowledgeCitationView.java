package com.shitulelv.aicollab.knowledge.application.view;

import java.util.UUID;

public record KnowledgeCitationView(
        UUID documentId,
        String filename,
        UUID chunkId,
        String heading,
        String quote,
        double similarity,
        int rank) {
}
