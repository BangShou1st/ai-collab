package com.shitulelv.aicollab.document.infrastructure.repository;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;

import java.util.UUID;

public record DocumentProcessingAttempt(DocumentStatus status, UUID processingToken, UUID uploadedBy) {
}
