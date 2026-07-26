package com.shitulelv.aicollab.document.application.view;

import java.util.UUID;

public record DocumentSearchHit(
        UUID id,
        UUID documentId,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        double similarity) {
}
