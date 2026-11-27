package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.agent.job.AiIndexJobService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class DocumentIndexSubmission {
    private final DocumentContentService content;
    private final DocumentRepository documents;
    private final AiIndexJobService jobs;

    DocumentIndexSubmission(DocumentContentService content, DocumentRepository documents, AiIndexJobService jobs) {
        this.content = content;
        this.documents = documents;
        this.jobs = jobs;
    }

    @Transactional
    public void submit(UUID projectId, UUID documentId, UUID token, byte[] source,
            List<DocumentChunk> chunks, String parserType) {
        content.saveParsed(projectId, documentId, token, source, chunks);
        if (!documents.markIndexing(projectId, documentId, token, parserType)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
        jobs.enqueueIndexDocument(projectId, documentId);
    }
}
