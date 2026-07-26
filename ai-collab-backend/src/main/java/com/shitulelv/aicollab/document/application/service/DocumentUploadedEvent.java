package com.shitulelv.aicollab.document.application.service;

import java.util.UUID;

public record DocumentUploadedEvent(UUID projectId, UUID documentId) {
}
