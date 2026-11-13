package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJob;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentDeletionFailureRecorder {
    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionFailureRecorder.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentDeletionJobRepository jobs;
    private final int maxAttempts;

    public DocumentDeletionFailureRecorder(
            DocumentDeletionJobRepository jobs,
            @Value("${document.deletion.max-attempts:5}") int maxAttempts) {
        this.jobs = jobs;
        this.maxAttempts = maxAttempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentDeletionJob record(DocumentDeletionJob job, RuntimeException failure) {
        int attempts = job.attempts() + 1;
        boolean exhausted = attempts >= maxAttempts;
        DocumentDeletionJobStatus status = exhausted
                ? DocumentDeletionJobStatus.FAILED
                : DocumentDeletionJobStatus.PENDING;
        int delaySeconds = exhausted ? 0 : backoffSeconds(attempts);
        String safeError = safeError(failure);
        if (!jobs.markFailure(job.id(), attempts, status, delaySeconds, safeError)) {
            log.warn("文档删除任务失败状态未写入（租约可能已回收），jobId={}", job.id());
        }
        return jobs.findById(job.id()).orElse(job);
    }

    static int backoffSeconds(int attempts) {
        return Math.min(3600, 30 * (1 << Math.min(Math.max(attempts - 1, 0), 7)));
    }

    static String safeError(RuntimeException failure) {
        String value;
        if (failure instanceof BusinessException businessException) {
            value = "ERROR:" + businessException.getErrorCode().name();
        } else if (failure instanceof IllegalStateException
                && failure.getMessage() != null
                && failure.getMessage().matches("[A-Z0-9_]{1,80}")) {
            value = "ERROR:" + failure.getMessage();
        } else {
            value = "ERROR:" + failure.getClass().getSimpleName();
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
