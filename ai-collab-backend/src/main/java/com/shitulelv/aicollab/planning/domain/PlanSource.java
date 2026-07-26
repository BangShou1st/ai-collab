package com.shitulelv.aicollab.planning.domain;

import java.util.UUID;

public record PlanSource(
        String ref, UUID documentId, String documentName, UUID chunkId, String heading,
        Double similarity, String quoteText, String contentHash) {
    public PlanSource(String ref, UUID documentId, String documentName, String quoteText) {
        this(ref, documentId, documentName, null, null, null, quoteText, null);
    }
}
