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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

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
    private final ConnectorOAuthTokenService userTokens;
    private final ConnectorVisibilityService visibility;
    private final ConnectorSyncMetrics metrics;
    private final Duration leaseRenewInterval;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public ConnectorSyncService(ConnectorSourceMapper sources, ConnectorConnectionMapper connections,
                                ConnectorSyncJobMapper jobs, ExternalDocumentMapper externalDocs,
                                ExternalDocumentRevisionMapper revisions, FeishuClient feishu,
                                com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets,
                                DocumentRepository documents, DocumentRegistrationService registration,
                                DocumentStorageGateway storage, DocumentTaskDispatcher dispatcher,
                                @Qualifier("connectorSyncExecutor") TaskExecutor syncExecutor,
                                ConnectorOAuthTokenService userTokens, ConnectorVisibilityService visibility,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,
                                ConnectorSyncMetrics metrics,
                                @Value("${connector.sync.lease-renew-interval-ms:180000}") long leaseRenewIntervalMs) {
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
        this.userTokens = userTokens;
        this.visibility = visibility;
        this.metrics = metrics;
        this.leaseRenewInterval = Duration.ofMillis(leaseRenewIntervalMs);
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Claims the run synchronously (fast conflict feedback) and executes the scan async. */
    public UUID trigger(UUID sourceId, String triggerType) {
        ConnectorRows.SourceRow source = requireSource(sourceId);
        ConnectorRows.JobRow running = jobs.findRunning(sourceId);
        if (running != null) {
            if (jobs.expireStale(running.id) == 1) {
                metrics.leaseReclaimed();
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
        try {
            syncExecutor.execute(() -> runSafely(jobId, sourceId, source));
        } catch (RejectedExecutionException exception) {
            // RUNNING 行已落库但线程池拒收：若不补偿，任务会以"无人执行"的状态空转
            // 到租约过期。立即收口为 FAILED（派发失败语义），并通过业务错误码明确
            // 告知调用方。此时该行租约新鲜且独占，不存在并发回收竞态。
            metrics.dispatchRejected();
            jobs.finish(jobId, "FAILED", 0, 0, 0, 0, 0,
                    "同步任务派发失败：执行队列已满或服务正在关闭，请稍后重试");
            log.warn("同步任务派发被拒绝，已补偿为 FAILED，sourceId={}，jobId={}", sourceId, jobId);
            throw new BusinessException(ErrorCode.CONNECTOR_SYNC_DISPATCH_FAILED);
        }
        return jobId;
    }

    private void runSafely(UUID jobId, UUID sourceId, ConnectorRows.SourceRow source) {
        Counters counters = new Counters();
        // 心跳随任务结束（成功/失败/失权）立即关闭：崩溃后不残留续期，租约按原窗口过期。
        try (ConnectorSyncLeaseGuard guard = ConnectorSyncLeaseGuard.start(jobs, jobId, metrics, leaseRenewInterval)) {
            String error;
            try {
                error = scan(jobId, source, counters, guard);
            } catch (ConnectorSyncLeaseGuard.LeaseLostException exception) {
                // 所有权已回收（过期被接管或任务被删除）：迟到的 finish/markSynced
                // 不会生效，也不应再产生新的业务写入，直接停止。
                log.warn("同步任务失去所有权，停止执行，sourceId={}，jobId={}", sourceId, jobId);
                metrics.ownershipLost();
                return;
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
            // finish 只在 status='RUNNING' 时生效：失去所有权的迟到收口会被数据库拒绝。
            int finished = jobs.finish(jobId, status, counters.scanned, counters.updated, counters.skipped,
                    counters.failed, counters.removed, errorSummary);
            // last_synced_at 只由真正收口了 RUNNING 任务的 Worker 刷新。
            if (finished == 1 && counters.sawChange) sources.markSynced(sourceId);
            metrics.finished(status);
        }
    }

    /** Returns an error summary when the whole scan failed, null when it completed. */
    private String scan(UUID jobId, ConnectorRows.SourceRow source, Counters counters, ConnectorSyncLeaseGuard guard) {
        ConnectorRows.ConnectionRow connection = connections.find(source.connectionId);
        if (connection == null || "INVALID".equals(connection.status)) {
            return "连接凭据不可用，请先在管理端重新验证连接";
        }
        String token;
        try {
            token = source.userAuthorizationId == null
                    ? feishu.tenantAccessToken(connection.appId, secrets.decrypt(connection.appSecretCipher))
                    : userTokens.accessToken(source.projectId, source.userAuthorizationId);
        } catch (FeishuApiException exception) {
            feishu.invalidateToken(connection.appId);
            if (exception.invalidApplicationCredentials()) {
                connections.updateStatus(connection.id, "INVALID", exception.getMessage(), null);
            }
            return "获取飞书访问凭证失败：" + exception.getMessage();
        } catch (BusinessException exception) {
            return "飞书用户授权暂不可用，请检查授权状态";
        }

        List<FeishuFile> files;
        try {
            files = feishu.listFolderDocx(token, source.folderToken);
        } catch (FeishuApiException exception) {
            if (source.userAuthorizationId != null && exception.invalidToken()) {
                blockInvalidUserToken(source);
                return "飞书用户凭证不可用，知识已屏蔽并等待重新验证";
            }
            if (exception.forbidden()) {
                visibility.blockSource(source.projectId, source.id, false);
                return "文件夹不可访问：" + exception.getMessage();
            }
            return "扫描文件夹失败：" + exception.getMessage();
        }

        // Listing completed in full: verification evidence is fresh.
        ensureOwnership(guard);
        boolean[] restored = { false };
        writeUnderOwnership(jobId, () -> {
            restored[0] = visibility.restoreSource(source.projectId, source.id);
        });
        if (!restored[0]) {
            return "知识源授权不可用或清理任务尚未完成";
        }
        feishu.folderPermissionMembers(token, source.folderToken);

        // BLOCKED rows re-join the diff so re-granted access restores them; only
        // rows already proven REMOVED stay out of consideration.
        List<ConnectorRows.ExternalDocRow> known = externalDocs.listBySource(source.id);
        Set<String> listedTokens = new HashSet<>();
        List<ConnectorRows.ExternalDocRow> toRemove = new ArrayList<>(known.stream()
                .filter(row -> !"REMOVED".equals(row.status)).toList());

        for (FeishuFile file : files) {
            counters.scanned++;
            ensureOwnership(guard);
            listedTokens.add(file.token());
            ConnectorRows.ExternalDocRow existing = known.stream()
                    .filter(row -> row.externalKey.equals(file.token())).findFirst().orElse(null);
            toRemove.removeIf(row -> row.externalKey.equals(file.token()));
            try {
                if (existing != null && existing.latestModifyTime != null
                        && existing.latestModifyTime.equals(file.modifiedTime())
                        && "ACTIVE".equals(existing.status)) {
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
                // 写入前的快速路径检查：内容拉取可能耗时数十秒，期间租约可能已被回收。
                ensureOwnership(guard);
                String hash = sha256(content);
                boolean contentUnchanged = existing != null && "ACTIVE".equals(existing.status)
                        && hash.equals(existing.contentHash);
                // 跨存储隔离：每次内容更新分配独有的不可变对象 Key，避免失权 Worker
                // 覆盖已由新 Worker 提交的权威文件。新文档与已有文档更新均在事务外写入独立 Key。
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                boolean reactivating = existing != null
                        && ("BLOCKED".equals(existing.status) || "REMOVED".equals(existing.status));
                UUID preparedDocumentId = (existing == null || reactivating)
                        ? UUID.randomUUID() : existing.documentId;
                UUID versionToken = UUID.randomUUID();
                String targetObjectKey = versionedObjectKey(source.projectId, preparedDocumentId, versionToken);
                if (!contentUnchanged) {
                    storage.put(targetObjectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
                }
                // 数据库 Fencing 短事务：锁定任务行校验所有权（RUNNING 且租约未过期）后，
                // 同一事务内完成受保护的数据库写入（包含 project_document.object_key 原子切换）；
                // 接管方必须等本事务提交才能回收任务。
                boolean[] updated = { false };
                boolean[] sourceActive = { true };
                try {
                    writeUnderOwnership(jobId, () -> {
                        sourceActive[0] = visibility.withActiveSource(source.projectId, source.id, () -> {
                            if (existing != null && "BLOCKED".equals(existing.status) && existing.documentStatus != null) {
                                if (!visibility.restoreDocument(source.projectId, source.id, existing.id)) {
                                    throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
                                }
                                existing.status = "ACTIVE";
                            }
                            if (contentUnchanged) {
                                externalDocs.updateContent(existing.id, safeTitle(file), file.url(),
                                        file.modifiedTime(), existing.contentHash);
                            } else {
                                upsertDocument(source, file, content, hash, existing, preparedDocumentId, targetObjectKey);
                                updated[0] = true;
                            }
                        });
                    });
                } catch (ConnectorSyncLeaseGuard.LeaseLostException exception) {
                    if (!contentUnchanged) {
                        // 失权 Worker 未能落库：安全清理本 Worker 刚才上传的独立对象 Key，
                        // 避免孤儿文件积累。由于使用不可变 Key，清理绝不会误删其他 Worker 已经提交的合法版本。
                        try {
                            storage.delete(targetObjectKey);
                        } catch (RuntimeException cleanupException) {
                            log.warn("失权 Worker 清理孤儿对象失败，objectKey={}", targetObjectKey, cleanupException);
                        }
                    }
                    throw exception;
                } catch (RuntimeException exception) {
                    if (!contentUnchanged) {
                        // 数据库事务异常回滚：补偿删除未被落库引用的对象
                        try {
                            storage.delete(targetObjectKey);
                        } catch (RuntimeException cleanupException) {
                            log.warn("事务回滚后清理孤儿对象失败，objectKey={}", targetObjectKey, cleanupException);
                        }
                    }
                    throw exception;
                }
                if (!sourceActive[0]) {
                    recordFailure(counters, "知识源授权已变化，请重新同步");
                } else if (updated[0]) {
                    counters.updated++;
                    counters.sawChange = true;
                } else {
                    counters.skipped++;
                }
            } catch (BusinessException exception) {
                recordFailure(counters, safeMessage(exception));
            } catch (FeishuApiException exception) {
                if (source.userAuthorizationId != null && exception.invalidToken()) {
                    blockInvalidUserToken(source);
                    return "飞书用户凭证不可用，知识已屏蔽并等待重新验证";
                }
                if (exception.forbidden()) {
                    if (existing != null) visibility.blockDocument(source.projectId, source.id, existing.id);
                    recordFailure(counters, "文档读取被拒绝（可能未共享给应用）");
                } else {
                    throw exception;
                }
            }
        }

        // Removal/revocation handling: only after a complete, successful listing.
        ensureOwnership(guard);
        for (ConnectorRows.ExternalDocRow stale : toRemove) {
            try {
                writeUnderOwnership(jobId, () ->
                        visibility.blockDocument(source.projectId, source.id, stale.id));
                counters.removed++;
                counters.sawChange = true;
            } catch (ConnectorSyncLeaseGuard.LeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.error("移除失效外部文档失败，externalDocumentId={}", stale.id, exception);
                recordFailure(counters, "清理已移除文档失败");
            }
        }
        return null;
    }

    private void blockInvalidUserToken(ConnectorRows.SourceRow source) {
        visibility.blockAuthorization(source.projectId, source.userAuthorizationId, false);
        try {
            userTokens.forceRefresh(source.projectId, source.userAuthorizationId);
        } catch (BusinessException failure) {
            // Temporary and uncertain failures keep the knowledge blocked without deleting it.
        }
    }

    /**
     * 数据库 Fencing 边界：短事务内先以行锁校验任务所有权（status=RUNNING 且租约未过期），
     * 校验通过才在同一事务内执行受保护的数据库写入。接管方（expireStale/新触发）必须等待
     * 本事务提交才能修改任务行，因此"校验通过后、写入前发生所有权转移"的窗口被数据库
     * 事务串行化消除。禁止在事务内执行飞书 HTTP 等外部请求。
     */
    private void writeUnderOwnership(UUID jobId, Runnable protectedWrites) {
        transactions.executeWithoutResult(tx -> {
            if (jobs.lockIfOwned(jobId) == null) {
                throw new ConnectorSyncLeaseGuard.LeaseLostException();
            }
            protectedWrites.run();
        });
    }

    private void upsertDocument(ConnectorRows.SourceRow source, FeishuFile file,
                                String content, String hash, ConnectorRows.ExternalDocRow existing,
                                UUID preparedDocumentId, String targetObjectKey) {
        String displayName = safeTitle(file);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        // A BLOCKED row means access was revoked earlier and its indexed document
        // was removed; on re-grant it must be re-registered and re-bound.
        boolean reactivating = existing != null && ("BLOCKED".equals(existing.status) || "REMOVED".equals(existing.status));
        if (existing == null || reactivating) {
            UUID documentId = preparedDocumentId;
            DocumentEntity entity = new DocumentEntity();
            entity.setId(documentId);
            entity.setProjectId(source.projectId);
            entity.setDisplayName(displayName);
            entity.setOriginalFilename(displayName + ".txt");
            entity.setMimeType("text/plain");
            entity.setSizeBytes((long) bytes.length);
            entity.setObjectKey(targetObjectKey);
            entity.setStatus(DocumentStatus.UPLOADED);
            entity.setChunkCount(0);
            entity.setUploadedBy(source.authorizationDeclaredBy);
            try {
                registration.registerUploadedDocument(entity);
            } catch (RuntimeException exception) {
                storage.delete(targetObjectKey);
                throw exception;
            }
            UUID externalId = UUID.randomUUID();
            if (reactivating) {
                externalDocs.updateDocumentRef(existing.id, documentId, "ACTIVE", displayName, file.url());
                revisions.insert(UUID.randomUUID(), existing.id,
                        revisions.maxRevisionNo(existing.id) + 1, file.modifiedTime(), hash, documentId, targetObjectKey, "ACTIVE");
            } else {
                externalDocs.insert(externalId, source.id, file.token(), file.type(), displayName,
                        file.url(), file.modifiedTime(), hash, documentId);
                revisions.insert(UUID.randomUUID(), externalId, 1, file.modifiedTime(), hash, documentId, targetObjectKey, "ACTIVE");
            }
        } else {
            if (!"READY".equals(existing.documentStatus)) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY,
                        "文档正在处理或索引失败，稍后同步会自动重试");
            }
            UUID documentId = existing.documentId;
            // 原子切换 project_document 的 object_key 与 size_bytes，递增 version 并重置为 UPLOADED
            if (!documents.resetForReindexWithSource(source.projectId, documentId, targetObjectKey, (long) bytes.length)) {
                throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
            }
            externalDocs.updateContent(existing.id, displayName, file.url(), file.modifiedTime(), hash);
            revisions.supersedeActive(existing.id);
            revisions.insert(UUID.randomUUID(), existing.id,
                    revisions.maxRevisionNo(existing.id) + 1, file.modifiedTime(), hash, documentId, targetObjectKey, "ACTIVE");
            dispatcher.dispatch(source.projectId, documentId);
        }
    }

    private ConnectorRows.SourceRow requireSource(UUID sourceId) {
        ConnectorRows.SourceRow row = sources.find(sourceId);
        if (row == null) throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_NOT_FOUND);
        return row;
    }

    /**
     * 写入前的所有权检查（Fencing）。租约心跳确凿判定失权后，任何新的文档写入、
     * 屏蔽或统计变更都必须停止：此时任务已被回收接管，继续写入会与新 Worker 产生
     * 不受控的并发副作用。检查与写入之间仍存在单次写入的残余竞态窗口，靠幂等的
     * 覆盖写语义（同 objectKey、同内容哈希）收敛，见模块注释。
     */
    private static void ensureOwnership(ConnectorSyncLeaseGuard guard) {
        if (!guard.holdsOwnership()) {
            throw new ConnectorSyncLeaseGuard.LeaseLostException();
        }
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

    private static String versionedObjectKey(UUID projectId, UUID documentId, UUID versionToken) {
        return "projects/" + projectId + "/documents/" + documentId + "/sources/" + versionToken + ".txt";
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
