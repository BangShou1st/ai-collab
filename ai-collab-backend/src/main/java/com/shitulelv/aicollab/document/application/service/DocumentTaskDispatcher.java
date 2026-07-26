package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DocumentTaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DocumentTaskDispatcher.class);
    private final TaskExecutor executor;
    private final DocumentProcessingService processing;

    public DocumentTaskDispatcher(
            @Qualifier("documentTaskExecutor") TaskExecutor executor,
            DocumentProcessingService processing) {
        this.executor = executor;
        this.processing = processing;
    }

    public void dispatch(UUID projectId, UUID documentId) {
        try {
            executor.execute(() -> processSafely(projectId, documentId));
        } catch (TaskRejectedException exception) {
            log.warn("文档调度暂时被拒绝，将由恢复任务重试，projectId={}，documentId={}",
                    projectId, documentId);
        }
    }

    private void processSafely(UUID projectId, UUID documentId) {
        try {
            processing.process(projectId, documentId);
        } catch (RuntimeException exception) {
            log.error("文档异步任务发生未预期异常，projectId={}，documentId={}，type={}",
                    projectId, documentId, exception.getClass().getSimpleName());
        }
    }
}
