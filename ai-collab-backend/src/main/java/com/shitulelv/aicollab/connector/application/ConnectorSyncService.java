package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuFile;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentRevisionMapper;
import com.shitulelv.aicollab.document.application.service.DocumentRegistrationService;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.application.service.DocumentUploadedEvent;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Folder snapshot diff sync. A sync never deletes indexed documents unless the
 * folder listing completed in full; network failures only fail the job. The
 * per-document indexing reuses the manual-upload pipeline: claim/heartbeat and
 * replaceAndComplete provide the atomic current-revision switch, and chunks of
 * the previous revision stay searchable until they are replaced.
 */
@Service
public class ConnectorSyncService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncService.class);
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private final ConnectorSourceMapper sources;
    private final ConnectorConnectionMapper connections;
    private final ConnectorSyncJobMapper jobs;
    private final ExternalDocumentMapper externalDocs;
    private final ExternalDocumentRevisionMapper revisions;
    private final FeishuClient feishu;
    private final com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets;
    private final DocumentRepository documents;
    private final DocumentRegistrationService registration;
    private final DocumentStorageGateway storage;
    private final DocumentTaskDispatcher dispatcher;
    private final TaskExecutor syncExecutor;

    public ConnectorSyncService(ConnectorSourceMapper sources, ConnectorConnectionMapper connections,
                                ConnectorSyncJobMapper jobs, ExternalDocumentMapper externalDocs,
                                ExternalDocumentRevisionMapper revisions, FeishuClient feishu,
                                com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets,
                                DocumentRepository documents, DocumentRegistrationService registration,
                                DocumentStorageGateway storage, DocumentTaskDispatcher dispatcher,
                                @Qualifier("connectorSyncExecutor") TaskExecutor syncExecutor) {
        this.sources = sources;
        this.connections = connections;
        this.jobs = jobs;
        this.externalDocs = externalDocs;
        this.revisions = revisions;
        this.feishu = feishu;
        this.secrets = secrets;
        this.documents = documents;
        this.registration = registration;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.syncExecutor = syncExecutor;
    }

    /** Claims the run synchronously (fast conflict feedback) and executes the scan async. */
    public UUID trigger(UUID sourceId, String triggerType) {
        ConnectorRows.SourceRow source = requireSource(sourceId);
        ConnectorRows.JobRow running = jobs.findRunning(sourceId);
        if (running != null) {
            if (jobs.expireStale(running.id) == 1) {
                log.warn("知识源同步租约过期已回收，sourceId={}", sourceId);
            } else {
                throw new BusinessException(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING);
            }
        }
        UUID jobId = UUID.randomUUID();
        try {
            jobs.insertRunning(jobId, sourceId, triggerType);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING);
        }
        syncExecutor.execute(() -> runSafely(jobId, sourceId, source));
        return jobId;
    }

    private void runSafely(UUID jobId, UUID sourceId, ConnectorRows.SourceRow source) {
        Counters counters = new Counters();
        String error = null;
        try {
            error = scan(source, counters);
        } catch (RuntimeException exception) {
            log.error("飞书知识源同步发生未预期异常，sourceId={}", sourceId, exception);
            error = "同步过程发生内部错误，请重试";
        }
        StringBuilder summary = new StringBuilder();
        if (error != null) summary.append(error);
        for (String item : counters.errors) {
            if (summary.length() > 0) summary.append("；");
            summary.append(item);
        }
        String errorSummary = summary.isEmpty() ? null : summary.toString();
        if (errorSummary != null && errorSummary.length() > 500) errorSummary = errorSummary.substring(0, 500);
        String status = counters.failed > 0 ? "PARTIAL_FAILED"
                : error == null ? "SUCCESS" : "FAILED";
        jobs.finish(jobId, status, counters.scanned, counters.updated, counters.skipped,
                counters.failed, counters.removed, errorSummary);
        if (counters.sawChange) sources.markSynced(sourceId);
    }

    /** Returns an error summary when the whole scan failed, null when it completed. */
    private String scan(ConnectorRows.SourceRow source, Counters counters) {
        ConnectorRows.ConnectionRow connection = connections.find(source.connectionId);
        if (connection == null || "INVALID".equals(connection.status)) {
            return "连接凭据不可用，请先在管理端重新验证连接";
        }
        String token;
        try {
            token = feishu.tenantAccessToken(connection.appId, secrets.decrypt(connection.appSecretCipher));
        } catch (FeishuApiException exception) {
            feishu.invalidateToken(connection.appId);
            connections.updateStatus(connection.id, "INVALID", exception.getMessage(), null);
            return "获取飞书访问凭证失败：" + exception.getMessage();
        }

        List<FeishuFile> files;
        try {
            files = feishu.listFolderDocx(token, source.folderToken);
        } catch (FeishuApiException exception) {
            if (exception.forbidden()) {
                blockSource(source, "文件夹访问被拒绝或授权已撤销，请重新确认共享范围");
                return "文件夹不可访问：" + exception.getMessage();
            }
            return "扫描文件夹失败：" + exception.getMessage();
        }

        // Listing completed in full: verification evidence is fresh.
        sources.updateStatus(source.id, "ACTIVE", java.time.OffsetDateTime.now());
        feishu.folderPermissionMembers(token, source.folderToken);

        // BLOCKED rows re-join the diff so re-granted access restores them; only
        // rows already proven REMOVED stay out of consideration.
        List<ConnectorRows.ExternalDocRow> known = externalDocs.listBySource(source.id).stream()
                .filter(row -> !"REMOVED".equals(row.status)).toList();
        Set<String> listedTokens = new HashSet<>();
        List<ConnectorRows.ExternalDocRow> toRemove = new ArrayList<>(known);

        for (FeishuFile file : files) {
            counters.scanned++;
            listedTokens.add(file.token());
            ConnectorRows.ExternalDocRow existing = known.stream()
                    .filter(row -> row.externalKey.equals(file.token())).findFirst().orElse(null);
            toRemove.removeIf(row -> row.externalKey.equals(file.token()));
            try {
                if (existing != null && existing.latestModifyTime != null
                        && existing.latestModifyTime.equals(file.modifiedTime())) {
                    counters.skipped++;
                    continue;
                }
                String content = feishu.docxRawContent(token, file.token());
                if (content.isBlank()) {
                    counters.skipped++;
                    continue;
                }
                if (content.length() > MAX_SOURCE_BYTES) {
                    recordFailure(counters, "文档过大，超过 2MB 文本上限");
                    continue;
                }
                String hash = sha256(content);
                if (existing != null && hash.equals(existing.contentHash)) {
                    externalDocs.updateContent(existing.id, safeTitle(file), file.url(),
                            file.modifiedTime(), existing.contentHash);
                    counters.skipped++;
                    continue;
                }
                upsertDocument(source, file, content, hash, existing);
                counters.updated++;
                counters.sawChange = true;
            } catch (BusinessException exception) {
                recordFailure(counters, safeMessage(exception));
            } catch (FeishuApiException exception) {
                if (exception.forbidden()) {
                    recordFailure(counters, "文档读取被拒绝（可能未共享给应用）");
                } else {
                    throw exception;
                }
            }
        }

        // Removal/revocation handling: only after a complete, successful listing.
        for (ConnectorRows.ExternalDocRow stale : toRemove) {
            try {
                removeIndexedDocument(source, stale);
                externalDocs.updateStatus(stale.id, "REMOVED");
                counters.removed++;
                counters.sawChange = true;
            } catch (RuntimeException exception) {
                log.error("移除失效外部文档失败，externalDocumentId={}", stale.id, exception);
                recordFailure(counters, "清理已移除文档失败");
            }
        }
        return null;
    }

    private void upsertDocument(ConnectorRows.SourceRow source, FeishuFile file,
                                String content, String hash, ConnectorRows.ExternalDocRow existing) {
        String displayName = safeTitle(file);
        // A BLOCKED row means access was revoked earlier and its indexed document
        // was removed; on re-grant it must be re-registered and re-bound.
        boolean reactivating = existing != null && "BLOCKED".equals(existing.status);
        if (existing == null || reactivating) {
            UUID documentId = UUID.randomUUID();
            String objectKey = objectKey(source.projectId, documentId);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            DocumentEntity entity = new DocumentEntity();
            entity.setId(documentId);
            entity.setProjectId(source.projectId);
            entity.setDisplayName(displayName);
            entity.setOriginalFilename(displayName + ".txt");
            entity.setMimeType("text/plain");
            entity.setSizeBytes((long) bytes.length);
            entity.setObjectKey(objectKey);
            entity.setStatus(DocumentStatus.UPLOADED);
            entity.setChunkCount(0);
            entity.setUploadedBy(source.authorizationDeclaredBy);
            try {
                registration.registerUploadedDocument(entity);
            } catch (RuntimeException exception) {
                storage.delete(objectKey);
                throw exception;
            }
            UUID externalId = UUID.randomUUID();
            if (reactivating) {
                externalDocs.updateDocumentRef(existing.id, documentId, "ACTIVE", displayName, file.url());
                revisions.insert(UUID.randomUUID(), existing.id,
                        revisions.maxRevisionNo(existing.id) + 1, file.modifiedTime(), hash, documentId, "ACTIVE");
            } else {
                externalDocs.insert(externalId, source.id, file.token(), file.type(), displayName,
                        file.url(), file.modifiedTime(), hash, documentId);
                revisions.insert(UUID.randomUUID(), externalId, 1, file.modifiedTime(), hash, documentId, "ACTIVE");
            }
        } else {
            if (!"READY".equals(existing.documentStatus)) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY,
                        "文档正在处理或索引失败，稍后同步会自动重试");
            }
            UUID documentId = existing.documentId;
            String objectKey = objectKey(source.projectId, documentId);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            if (!documents.resetForReindex(source.projectId, documentId)) {
                throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
            }
            externalDocs.updateContent(existing.id, displayName, file.url(), file.modifiedTime(), hash);
            revisions.supersedeActive(existing.id);
            revisions.insert(UUID.randomUUID(), existing.id,
                    revisions.maxRevisionNo(existing.id) + 1, file.modifiedTime(), hash, documentId, "ACTIVE");
            dispatcher.dispatch(source.projectId, documentId);
        }
    }

    private void removeIndexedDocument(ConnectorRows.SourceRow source, ConnectorRows.ExternalDocRow stale) {
        if (stale.documentId != null) {
            // project_document rows are only removable in DELETING state; mark first.
            documents.markDeleting(source.projectId, stale.documentId);
            documents.deleteRows(source.projectId, stale.documentId);
            try {
                storage.delete(objectKey(source.projectId, stale.documentId));
            } catch (RuntimeException exception) {
                log.warn("删除外部文档对象失败（不影响索引移除），objectKey 前缀已脱敏");
            }
        }
    }

    private void blockSource(ConnectorRows.SourceRow source, String reason) {
        for (ConnectorRows.ExternalDocRow doc : externalDocs.listBySource(source.id)) {
            if ("ACTIVE".equals(doc.status)) {
                removeIndexedDocument(source, doc);
                externalDocs.updateStatus(doc.id, "BLOCKED");
            }
        }
        sources.updateStatus(source.id, "BLOCKED", null);
        log.warn("飞书知识源已被阻断：{}，sourceId={}", reason, source.id);
    }

    private ConnectorRows.SourceRow requireSource(UUID sourceId) {
        ConnectorRows.SourceRow row = sources.find(sourceId);
        if (row == null) throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_NOT_FOUND);
        return row;
    }

    private void recordFailure(Counters counters, String message) {
        counters.failed++;
        counters.sawChange = true;
        counters.errors.add(message);
    }

    private static String safeMessage(BusinessException exception) {
        return exception.getMessage() == null ? "文档同步失败" : exception.getMessage();
    }

    private static String safeTitle(FeishuFile file) {
        String name = file.name() == null || file.name().isBlank() ? "未命名文档" : file.name().strip();
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String objectKey(UUID projectId, UUID documentId) {
        return "projects/" + projectId + "/documents/" + documentId + "/source.txt";
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    static final class Counters {
        int scanned;
        int updated;
        int skipped;
        int failed;
        int removed;
        boolean sawChange;
        final List<String> errors = new ArrayList<>();

        List<String> errors() {
            return errors;
        }
    }
}
