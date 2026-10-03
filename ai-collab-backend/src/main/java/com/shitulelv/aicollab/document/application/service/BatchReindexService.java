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
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @org.springframework.transaction.annotation.Transactional
    public int reindexAll(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        return reindexProject(projectId, userId);
    }

    /**
     * 系统级全量重建：调用方须已验证 systemAdmin，按项目逐个复用同一入队逻辑。
     */
    @org.springframework.transaction.annotation.Transactional
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
        if (jdbc!=null) {
            jdbc.update("INSERT INTO document_reindex_batch(project_id,total) VALUES (?,?) ON CONFLICT(project_id) DO UPDATE SET total=EXCLUDED.total,created_at=now()",projectId,readyDocs.size());
            jdbc.update("DELETE FROM document_reindex_batch_item WHERE project_id=?",projectId);
        }

        audit.write(projectId, userId, "DOCUMENT_BATCH_REINDEX_REQUESTED", "PROJECT", projectId,
                Map.of("documentCount", readyDocs.size()));

        for (DocumentEntity doc : readyDocs) {
            if (documents.resetForReindex(projectId, doc.getId())) {
                progress.documents.put(doc.getId(), doc.getVersion() + 1);
                if (jdbc!=null) jdbc.update("INSERT INTO document_reindex_batch_item(project_id,document_id,source_version) VALUES (?,?,?)",projectId,doc.getId(),doc.getVersion()+1);
                events.publishEvent(new DocumentUploadedEvent(projectId, doc.getId()));
            } else {
                progress.failedCount.incrementAndGet();
            }
        }

        log.info("Batch reindex queued for projectId={} totalDocuments={}", projectId, readyDocs.size());
        return readyDocs.size();
    }

    public BatchProgress getProgress(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        BatchProgress progress = progressMap.get(projectId);
        if (jdbc!=null) {
            var totals=jdbc.queryForList("SELECT total FROM document_reindex_batch WHERE project_id=?",Integer.class,projectId);
            if (!totals.isEmpty()) {
                progress=new BatchProgress(totals.getFirst());
                for (var row:jdbc.queryForList("SELECT document_id,source_version FROM document_reindex_batch_item WHERE project_id=?",projectId))
                    progress.documents.put((UUID)row.get("document_id"),(Integer)row.get("source_version"));
            }
        }
        if (progress == null) return new BatchProgress(0);
        int completed = 0;
        int failed = progress.total - progress.documents.size();
        int queued = 0;
        int processing = 0;
        for (var entry : progress.documents.entrySet()) {
            DocumentEntity document = documents.find(projectId, entry.getKey()).orElse(null);
            if (document == null || document.getVersion() != entry.getValue() || document.getStatus() == DocumentStatus.FAILED) failed++;
            else if (document.getStatus() == DocumentStatus.READY) completed++;
            else if (document.getStatus() == DocumentStatus.UPLOADED) queued++;
            else processing++;
        }
        progress.completedCount.set(completed); progress.failedCount.set(failed);
        progress.queuedCount.set(queued); progress.processingCount.set(processing);
        return progress;
    }

    public void clearProgress(UUID projectId) {
        progressMap.remove(projectId);
        if (jdbc!=null) jdbc.update("DELETE FROM document_reindex_batch WHERE project_id=?",projectId);
    }

    public static class BatchProgress {
        public final int total;
        public final java.util.concurrent.atomic.AtomicInteger completedCount = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger failedCount = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger queuedCount = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger processingCount = new java.util.concurrent.atomic.AtomicInteger();
        private final Map<UUID,Integer> documents = new ConcurrentHashMap<>();

        public BatchProgress(int total) {
            this.total = total;
        }

        public int getCompleted() { return completedCount.get(); }
        public int getFailed() { return failedCount.get(); }
        public int getInProgress() { return Math.max(0, total - completedCount.get() - failedCount.get()); }
        public int getQueued() { return queuedCount.get(); }
        public int getProcessing() { return processingCount.get(); }
    }
}
