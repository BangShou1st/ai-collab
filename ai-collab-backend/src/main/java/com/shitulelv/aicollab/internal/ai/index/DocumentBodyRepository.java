package com.shitulelv.aicollab.internal.ai.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 正文块读取（P2 契约 §4.3）。只读 public.document_body / document_body_chunk / project_document，
 * 可见性条件与 {@code DocumentContentService.status} 一致：非 DELETING、未封锁、外部来源授权有效。
 */
@Repository
public class DocumentBodyRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DocumentBodyRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Header(UUID projectId, UUID snapshotId, String parseVersion) {
    }

    /** 整篇文档每行的 id / chunk_no / metadata，用于解析 parentChunkNo。 */
    public record RowMeta(UUID id, int chunkNo, Map<String, Object> metadata) {
    }

    public record Row(int chunkNo, String heading, String content, String contentHash, Map<String, Object> metadata) {
    }

    public Optional<Header> findReadableHeader(UUID documentId) {
        return jdbc.query("""
                SELECT d.project_id, b.snapshot_id, b.parse_version
                FROM project_document d JOIN document_body b ON b.document_id=d.id
                WHERE d.id=? AND d.status<>'DELETING' AND NOT d.access_blocked
                  AND NOT EXISTS (SELECT 1 FROM external_document e
                    JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
                    LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                    WHERE e.document_id=d.id AND (e.status<>'ACTIVE' OR s.status<>'ACTIVE'
                      OR (s.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                """, (rs, i) -> new Header(rs.getObject("project_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class), rs.getString("parse_version")), documentId)
                .stream().findFirst();
    }

    public List<RowMeta> listRowMeta(UUID documentId) {
        return jdbc.query("SELECT id, chunk_no, metadata::text AS metadata FROM document_body_chunk "
                        + "WHERE document_id=? ORDER BY chunk_no",
                (rs, i) -> new RowMeta(rs.getObject("id", UUID.class), rs.getInt("chunk_no"),
                        parse(rs.getString("metadata"))), documentId);
    }

    public List<Row> page(UUID documentId, int afterChunkNo, int limit) {
        return jdbc.query("SELECT chunk_no, heading, content, content_hash, metadata::text AS metadata "
                        + "FROM document_body_chunk WHERE document_id=? AND chunk_no>? ORDER BY chunk_no LIMIT ?",
                (rs, i) -> new Row(rs.getInt("chunk_no"), rs.getString("heading"), rs.getString("content"),
                        rs.getString("content_hash"), parse(rs.getString("metadata"))),
                documentId, afterChunkNo, limit);
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, MAP);
        } catch (Exception invalid) {
            return Map.of();
        }
    }
}
