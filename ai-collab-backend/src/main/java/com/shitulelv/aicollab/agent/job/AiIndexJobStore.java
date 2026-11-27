package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引任务所需的业务表读写（只碰 {@code public.project_document / document_body / document_body_chunk / audit_log}，
 * 绝不碰 {@code ai_index}）。
 */
@Repository
public class AiIndexJobStore {

    public static final int PRUNE_BATCH_SIZE = IndexAdminClient.MAX_BATCH_DOCUMENTS;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    @Autowired
    public AiIndexJobStore(JdbcTemplate jdbc, ObjectProvider<AuditService> auditProvider) {
        this(jdbc, auditProvider == null ? null : auditProvider.getIfAvailable());
    }

    public AiIndexJobStore(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = audit;
    }

    public record IndexDocumentBodyInfo(UUID projectId, UUID documentId, UUID bodySnapshotId,
            String parseVersion, int bodyChunkCount) {
    }

    public record DocumentBodyState(UUID projectId, String status, boolean accessBlocked,
            UUID snapshotId, int bodyChunkCount) {
    }

    public record DocumentRef(UUID documentId, UUID projectId) {
    }

    public record PruneBatch(IndexAdminClient.DocumentRange range, List<UUID> documentIds) {
    }

    public record PruneSnapshot(OffsetDateTime asOf, List<PruneBatch> batches) {
    }

    /** 读取 {@code INDEX_DOCUMENT} 任务快照：文档必须属于指定项目、非 DELETING、未封锁、外部来源有效且存在正文。 */
    public Optional<IndexDocumentBodyInfo> findIndexDocumentSnapshot(UUID projectId, UUID documentId) {
        if (projectId == null || documentId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT d.project_id, d.id AS document_id, b.snapshot_id, b.parse_version,
                       (SELECT count(*) FROM document_body_chunk c WHERE c.document_id = d.id) AS chunk_count
                  FROM project_document d
                  JOIN document_body b ON b.document_id = d.id
                 WHERE d.id = ? AND d.project_id = ?
                   AND d.status <> 'DELETING' AND NOT d.access_blocked
                   AND NOT EXISTS (
                       SELECT 1 FROM external_document e
                         JOIN connector_source s ON s.id = e.source_id AND s.project_id = d.project_id
                         LEFT JOIN connector_oauth_authorization a
                           ON a.id = s.user_authorization_id AND a.project_id = s.project_id
                        WHERE e.document_id = d.id
                          AND (e.status <> 'ACTIVE' OR s.status <> 'ACTIVE'
                               OR (s.user_authorization_id IS NOT NULL
                                   AND (a.id IS NULL OR a.status <> 'ACTIVE' OR a.access_expires_at <= now()))))
                """, (rs, row) -> new IndexDocumentBodyInfo(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getString("parse_version"),
                        rs.getInt("chunk_count")),
                documentId, projectId).stream().findFirst();
    }

    /** 校验 {@code INDEX_DOCUMENT} result 时的当前快照与正文块数。 */
    public Optional<DocumentBodyState> findDocumentBodyState(UUID documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT d.project_id, d.status, d.access_blocked, b.snapshot_id,
                       (SELECT count(*) FROM document_body_chunk c WHERE c.document_id = d.id) AS chunk_count
                  FROM project_document d
                  JOIN document_body b ON b.document_id = d.id
                 WHERE d.id = ?
                """, (rs, row) -> new DocumentBodyState(
                        rs.getObject("project_id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("access_blocked"),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getInt("chunk_count")),
                documentId).stream().findFirst();
    }

    /**
     * 常规上传 {@code INDEX_DOCUMENT} 成功：将文档从 {@code INDEXING} 置为 {@code READY} 并写审计
     * {@code DOCUMENT_INDEXED}。重建子任务不调用此方法。
     */
    public boolean markDocumentReadyFromIndex(UUID projectId, UUID documentId, String embeddingModel,
            int dimension, int chunkCount, OffsetDateTime now) {
        List<Map<String, Object>> updated = jdbc.queryForList("""
                UPDATE project_document
                   SET status = 'READY',
                       chunk_count = ?,
                       embedding_provider = NULL,
                       embedding_model = ?,
                       embedding_dimension = ?,
                       embedding_fingerprint = NULL,
                       indexed_at = ?,
                       error_message = NULL,
                       processing_token = NULL,
                       processing_heartbeat_at = NULL,
                       updated_at = ?
                 WHERE project_id = ? AND id = ? AND status = 'INDEXING' AND NOT access_blocked
                RETURNING uploaded_by, original_filename
                """, chunkCount, embeddingModel, dimension, now, now, projectId, documentId);
        if (updated.isEmpty()) {
            return false;
        }
        if (audit != null) {
            Map<String, Object> row = updated.getFirst();
            UUID uploadedBy = row.get("uploaded_by") instanceof UUID u ? u : null;
            String filename = row.get("original_filename") instanceof String s ? s : "未知文件";
            if (uploadedBy != null) {
                audit.write(projectId, uploadedBy, "DOCUMENT_INDEXED", "PROJECT_DOCUMENT", documentId,
                        Map.of("originalFilename", filename));
            }
        }
        return true;
    }

    /** 常规上传 {@code INDEX_DOCUMENT} 终态失败：置文档 {@code FAILED}，{@code error_message} 用固定文案。 */
    public boolean markDocumentFailedFromIndex(UUID projectId, UUID documentId, String fixedErrorMessage,
            OffsetDateTime now) {
        List<Map<String, Object>> updated = jdbc.queryForList("""
                UPDATE project_document
                   SET status = 'FAILED',
                       error_message = ?,
                       processing_token = NULL,
                       processing_heartbeat_at = NULL,
                       updated_at = ?
                 WHERE project_id = ? AND id = ? AND status IN ('PARSING', 'INDEXING')
                RETURNING uploaded_by
                """, fixedErrorMessage, now, projectId, documentId);
        if (updated.isEmpty()) {
            return false;
        }
        if (audit != null) {
            Object raw = updated.getFirst().get("uploaded_by");
            if (raw instanceof UUID uploadedBy) {
                audit.write(projectId, uploadedBy, "DOCUMENT_PROCESSING_FAILED",
                        "PROJECT_DOCUMENT", documentId);
            }
        }
        return true;
    }

    /** 按 PostgreSQL UUID 升序分页读取当前 {@code READY} 文档（用于重建扇出与 {@code :reconcile}）。 */
    public List<DocumentRef> listReadyDocuments(UUID afterDocumentId, int limit) {
        int bounded = Math.max(1, Math.min(limit, IndexAdminClient.MAX_BATCH_DOCUMENTS));
        if (afterDocumentId == null) {
            return jdbc.query("""
                    SELECT id, project_id FROM project_document
                     WHERE status = 'READY'
                     ORDER BY id ASC
                     LIMIT ?
                    """, (rs, row) -> new DocumentRef(
                            rs.getObject("id", UUID.class),
                            rs.getObject("project_id", UUID.class)),
                    bounded);
        }
        return jdbc.query("""
                SELECT id, project_id FROM project_document
                 WHERE status = 'READY' AND id > ?
                 ORDER BY id ASC
                 LIMIT ?
                """, (rs, row) -> new DocumentRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class)),
                afterDocumentId, bounded);
    }

    /**
     * PRUNE 兜底一致性快照（R4-3）：在同一个 {@code REPEATABLE READ} 只读事务内，先取 PostgreSQL
     * 服务器时间 {@code SELECT now()} 作为 {@code asOf}，再按 UUID 升序每批 ≤ 5000 切片。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PruneSnapshot snapshotIndexableForPrune() {
        OffsetDateTime asOf = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        if (asOf == null) {
            throw new IllegalStateException("SELECT now() 返回 null");
        }
        List<PruneBatch> batches = new ArrayList<>();
        UUID afterId = null;
        while (true) {
            List<UUID> page = queryIndexableIds(afterId, PRUNE_BATCH_SIZE + 1);
            boolean hasMore = page.size() > PRUNE_BATCH_SIZE;
            List<UUID> batchIds = hasMore ? List.copyOf(page.subList(0, PRUNE_BATCH_SIZE)) : List.copyOf(page);
            UUID throughId = hasMore ? batchIds.getLast() : null;
            batches.add(new PruneBatch(new IndexAdminClient.DocumentRange(afterId, throughId), batchIds));
            if (!hasMore) {
                break;
            }
            afterId = throughId;
        }
        return new PruneSnapshot(asOf, List.copyOf(batches));
    }

    private List<UUID> queryIndexableIds(UUID afterId, int limit) {
        if (afterId == null) {
            return jdbc.query("""
                    SELECT id FROM project_document
                     WHERE status IN ('UPLOADED', 'PARSING', 'INDEXING', 'READY')
                     ORDER BY id ASC
                     LIMIT ?
                    """, (rs, row) -> rs.getObject(1, UUID.class), limit);
        }
        return jdbc.query("""
                SELECT id FROM project_document
                 WHERE status IN ('UPLOADED', 'PARSING', 'INDEXING', 'READY')
                   AND id > ?
                 ORDER BY id ASC
                 LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), afterId, limit);
    }
}
