package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BatchReindexService {
    private static final Logger log = LoggerFactory.getLogger(BatchReindexService.class);

    private final DocumentRepository documents;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ProjectAccessGuard access;
    private final ProjectWriteGuard writeGuard;

    private final ConcurrentHashMap<UUID, BatchProgress> progressMap = new ConcurrentHashMap<>();

    public BatchReindexService(DocumentRepository documents, AuditService audit,
                               ApplicationEventPublisher events, ProjectAccessGuard access,
                               ProjectWriteGuard writeGuard) {
        this.documents = documents;
        this.audit = audit;
        this.events = events;
        this.access = access;
        this.writeGuard = writeGuard;
    }

    public int reindexAll(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        return reindexProject(projectId, userId);
    }

    /**
     * 系统级全量重建：调用方须已验证 systemAdmin，按项目逐个复用同一入队逻辑。
     */
    public int reindexAllProjects(UUID operatorId) {
        int total = 0;
        for (UUID projectId : documents.projectIdsWithReadyDocuments()) {
            total += reindexProject(projectId, operatorId);
        }
        return total;
    }

    private int reindexProject(UUID projectId, UUID userId) {
        List<DocumentEntity> readyDocs = documents.list(projectId).stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY)
                .toList();
        if (readyDocs.isEmpty()) return 0;

        BatchProgress progress = new BatchProgress(readyDocs.size());
        progressMap.put(projectId, progress);

        audit.write(projectId, userId, "DOCUMENT_BATCH_REINDEX_REQUESTED", "PROJECT", projectId,
                Map.of("documentCount", readyDocs.size()));

        for (DocumentEntity doc : readyDocs) {
            if (documents.resetForReindex(projectId, doc.getId())) {
                events.publishEvent(new DocumentUploadedEvent(projectId, doc.getId()));
            } else {
                progress.failedCount.incrementAndGet();
            }
            progress.completedCount.incrementAndGet();
        }

        log.info("Batch reindex queued for projectId={} totalDocuments={}", projectId, readyDocs.size());
        return readyDocs.size();
    }

    public BatchProgress getProgress(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        BatchProgress progress = progressMap.get(projectId);
        if (progress == null) return new BatchProgress(0);
        return progress;
    }

    public void clearProgress(UUID projectId) {
        progressMap.remove(projectId);
    }

    public static class BatchProgress {
        public final int total;
        public final java.util.concurrent.atomic.AtomicInteger completedCount = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger failedCount = new java.util.concurrent.atomic.AtomicInteger();

        public BatchProgress(int total) {
            this.total = total;
        }

        public int getCompleted() { return completedCount.get(); }
        public int getFailed() { return failedCount.get(); }
        public int getInProgress() { return Math.max(0, total - completedCount.get()); }
    }
}
