package com.shitulelv.aicollab.knowledge.application.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeCitationView(
        UUID documentId,
        String filename,
        UUID chunkId,
        String heading,
        String quote,
        double similarity,
        int rank,
        Integer pageNumber) {
    public KnowledgeCitationView(
            UUID documentId, String filename, UUID chunkId,
            String heading, String quote, double similarity, int rank) {
        this(documentId, filename, chunkId, heading, quote, similarity, rank, null);
    }
}
