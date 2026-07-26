package com.shitulelv.aicollab.document.application.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentProcessingListener {
    private final DocumentTaskDispatcher dispatcher;
    public DocumentProcessingListener(DocumentTaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUpload(DocumentUploadedEvent event) {
        dispatcher.dispatch(event.projectId(), event.documentId());
    }
}
