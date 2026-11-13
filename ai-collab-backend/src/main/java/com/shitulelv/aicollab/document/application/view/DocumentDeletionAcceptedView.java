package com.shitulelv.aicollab.document.application.view;

import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;

import java.util.UUID;

public record DocumentDeletionAcceptedView(UUID deletionId, DocumentDeletionJobStatus status) {
}
