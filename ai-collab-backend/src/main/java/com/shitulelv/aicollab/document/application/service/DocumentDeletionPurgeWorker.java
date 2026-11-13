package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJob;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJobRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class DocumentDeletionPurgeWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionPurgeWorker.class);

    private final DocumentDeletionJobRepository jobs;
    private final DocumentStorageGateway storage;
    private final DocumentDeletionCompletionService completion;
    private final DocumentDeletionFailureRecorder failures;
    private final boolean schedulerEnabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int retentionDays;

    public DocumentDeletionPurgeWorker(
            DocumentDeletionJobRepository jobs,
            DocumentStorageGateway storage,
            DocumentDeletionCompletionService completion,
            DocumentDeletionFailureRecorder failures,
            @Value("${document.deletion.scheduler-enabled:true}") boolean schedulerEnabled,
            @Value("${document.deletion.batch-size:20}") int batchSize,
            @Value("${document.deletion.lease-seconds:60}") int leaseSeconds,
            @Value("${document.deletion.retention-days:7}") int retentionDays) {
        this.jobs = jobs;
        this.storage = storage;
        this.completion = completion;
        this.failures = failures;
        this.schedulerEnabled = schedulerEnabled;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${document.deletion.purge-interval-ms:30000}",
            initialDelayString = "${document.deletion.purge-initial-delay-ms:30000}")
    public void purgeDue() {
        if (!schedulerEnabled) {
            return;
        }
        processDueBatch();
    }

    @Scheduled(fixedDelayString = "${document.deletion.reconcile-interval-ms:3600000}",
            initialDelayString = "${document.deletion.reconcile-initial-delay-ms:3600000}")
    @Transactional
    public void reconcilePeriodically() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            reconcileNow();
        } catch (RuntimeException exception) {
            log.warn("文档删除任务对账失败，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileOnStartup() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            reconcileNow();
        } catch (RuntimeException exception) {
            log.warn("文档删除任务启动对账失败，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    public int processDueBatch() {
        List<DocumentDeletionJob> claimed = jobs.claimBatch(batchSize, leaseSeconds);
        int completed = 0;
        for (DocumentDeletionJob job : claimed) {
            if (process(job)) {
                completed++;
            }
        }
        return completed;
    }

    @Transactional
    public ReconcileResult reconcileNow() {
        int leased = jobs.recoverExpiredLeases();
        int orphaned = jobs.reconcileOrphans();
        int expired = jobs.deleteCompletedBefore(
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(retentionDays));
        return new ReconcileResult(leased, orphaned, expired);
    }

    private boolean process(DocumentDeletionJob job) {
        if (!safeObjectKey(job)) {
            failures.record(job, new IllegalStateException("OBJECT_KEY_MISMATCH"));
            log.warn("文档删除任务对象键未通过安全校验，jobId={}", job.id());
            return false;
        }
        try {
            try {
                storage.delete(job.objectKey());
            } catch (BusinessException exception) {
                if (exception.getErrorCode() != ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND) {
                    throw exception;
                }
            }
            completion.purgeAndComplete(job);
            return true;
        } catch (RuntimeException exception) {
            try {
                failures.record(job, exception);
            } catch (RuntimeException recordingFailure) {
                // The RUNNING lease is the recovery path if the failure marker itself cannot be written.
                log.warn("文档删除任务失败状态写入异常，jobId={}，异常类型={}",
                        job.id(), recordingFailure.getClass().getSimpleName());
            }
            log.warn("文档删除任务未完成，jobId={}，异常类型={}",
                    job.id(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean safeObjectKey(DocumentDeletionJob job) {
        String prefix = "projects/" + job.projectId() + "/documents/" + job.documentId() + "/";
        return job.objectKey() != null && job.objectKey().startsWith(prefix);
    }

    public record ReconcileResult(int recoveredLeases, int orphanedDocuments, int expiredJobs) {
    }
}
