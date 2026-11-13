package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJob;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJobRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class DocumentDeletionCompletionService {
    private final DocumentRepository documents;
    private final DocumentDeletionService deletionService;
    private final DocumentDeletionJobRepository jobs;

    public DocumentDeletionCompletionService(DocumentRepository documents,
                                             DocumentDeletionService deletionService,
                                             DocumentDeletionJobRepository jobs) {
        this.documents = documents;
        this.deletionService = deletionService;
        this.jobs = jobs;
    }

    /**
     * Row removal, the existing deletion audit, and DONE transition share one transaction.
     * If the row was already removed, DONE is still a valid idempotent outcome.
     */
    @Transactional
    public void purgeAndComplete(DocumentDeletionJob job) {
        Optional<DocumentEntity> document = documents.find(job.projectId(), job.documentId());
        if (document.isPresent()) {
            DocumentEntity entity = document.get();
            if (entity.getStatus() != DocumentStatus.DELETING) {
                throw new IllegalStateException("DOCUMENT_NOT_DELETING");
            }
            deletionService.deleteRows(job.projectId(), job.documentId(), entity.getUploadedBy());
        }
        if (!jobs.markDone(job.id())) {
            throw new IllegalStateException("JOB_NOT_RUNNING");
        }
    }
}
