package com.shitulelv.aicollab.retrieval.remote;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link KnowledgeHitVerifier.HitVerificationStore} 的 JDBC 实现。只读 {@code project_document}、{@code document_body}、
 * {@code document_body_chunk}，不读 {@code document_chunk}（契约 §6）。可见性条件与
 * {@code EvidenceVerificationService} 现有 SQL 一致：READY、未封锁、连接器可见性可读。
 */
@Repository
public class JdbcKnowledgeHitRepository implements KnowledgeHitVerifier.HitVerificationStore {

    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcKnowledgeHitRepository(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Map<UUID, UUID> readableSnapshots(UUID projectId, Collection<UUID> documentIds) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("documentIds", documentIds);
        namedJdbc.query("""
                SELECT pd.id AS document_id, b.snapshot_id AS snapshot_id
                FROM project_document pd
                JOIN document_body b ON b.document_id = pd.id
                LEFT JOIN connector_document_visibility v
                       ON v.id = pd.id AND v.project_id = pd.project_id
                WHERE pd.project_id = :projectId
                  AND pd.id IN (:documentIds)
                  AND pd.status = 'READY'
                  AND COALESCE(pd.access_blocked, false) = false
                  AND COALESCE(v.readable, true) = true
                """, params, rs -> {
            result.put(rs.getObject("document_id", UUID.class), rs.getObject("snapshot_id", UUID.class));
        });
        return result;
    }

    @Override
    public List<KnowledgeHitVerifier.BodyRow> bodyRows(Collection<UUID> documentIds, Collection<Integer> chunkNos) {
        if (documentIds == null || documentIds.isEmpty() || chunkNos == null || chunkNos.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentIds", documentIds)
                .addValue("chunkNos", chunkNos);
        return namedJdbc.query("""
                SELECT c.document_id, c.chunk_no, c.heading, c.content, c.content_hash
                FROM document_body_chunk c
                WHERE c.document_id IN (:documentIds)
                  AND c.chunk_no IN (:chunkNos)
                """, params, (rs, rowNum) -> new KnowledgeHitVerifier.BodyRow(
                rs.getObject("document_id", UUID.class),
                rs.getInt("chunk_no"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("content_hash")));
    }
}
