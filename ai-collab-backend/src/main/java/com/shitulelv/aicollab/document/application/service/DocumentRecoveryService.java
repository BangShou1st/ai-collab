package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class DocumentRecoveryService {
    private final DocumentRepository documents;
    private final DocumentProcessingService processing;
    private final DocumentFailureRecorder failures;

    public DocumentRecoveryService(DocumentRepository documents, DocumentProcessingService processing,
                                   DocumentFailureRecorder failures) {
        this.documents = documents;
        this.processing = processing;
        this.failures = failures;
    }

    @Scheduled(fixedDelayString = "${document.dispatch-interval:30s}",
            initialDelayString = "${document.dispatch-initial-delay:0s}")
    public void dispatchRecoverableWork() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(15);
        for (DocumentRecoveryCandidate candidate : documents.recoverable(staleBefore)) {
            if (candidate.status() == DocumentStatus.UPLOADED) {
                processing.process(candidate.projectId(), candidate.documentId());
            } else {
                failures.recordStale(candidate, staleBefore);
            }
        }
    }
}
