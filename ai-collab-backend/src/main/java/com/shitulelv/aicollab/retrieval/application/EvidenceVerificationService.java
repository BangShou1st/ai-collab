package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.retrieval.EvidenceRef;
import com.shitulelv.aicollab.common.retrieval.SeedEvidenceInput;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 跨模块证据核验服务：所有进入 Planning 的外部或会话种子证据，必须重新验证当前项目归属、
 * 文档 READY 且未被撤权/封锁，并以数据库中的真实分片正文与 contentHash 为准。
 */
@Service
public class EvidenceVerificationService {

    public static final int MAX_SEED_CANDIDATES = 20;

    private final ProjectAccessGuard access;
    private final NamedParameterJdbcTemplate namedJdbc;

    public EvidenceVerificationService(ProjectAccessGuard access, JdbcTemplate jdbc) {
        this.access = access;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * 核验种子证据并重建权威 {@link EvidenceRef} 列表。不抛出跨项目提示细节，
     * 对失效、撤权、非 READY 或哈希漂移的分片静默剔除（fail-closed）。
     */
    public List<EvidenceRef> verifySeedEvidence(
            UUID projectId, UUID userId, List<SeedEvidenceInput> candidates, int maxItems) {
        access.requireMember(projectId, userId);
        if (candidates == null || candidates.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        int boundedLimit = Math.min(maxItems, MAX_SEED_CANDIDATES);
        var deduped = new LinkedHashMap<ChunkKey, SeedEvidenceInput>();
        for (SeedEvidenceInput item : candidates) {
            if (item == null || !item.hasIdentity()) {
                continue;
            }
            deduped.putIfAbsent(new ChunkKey(item.documentId(), item.chunkNo()), item);
            if (deduped.size() >= MAX_SEED_CANDIDATES) {
                break;
            }
        }
        if (deduped.isEmpty()) {
            return List.of();
        }
        var rowsByChunk = fetchVerifiedChunks(projectId, List.copyOf(deduped.keySet()));
        List<EvidenceRef> verified = new ArrayList<>();
        for (SeedEvidenceInput candidate : deduped.values()) {
            if (verified.size() >= boundedLimit) {
                break;
            }
            DbChunkRow row = rowsByChunk.get(new ChunkKey(candidate.documentId(), candidate.chunkNo()));
            if (row == null || !candidate.contentHash().equalsIgnoreCase(row.contentHash().strip())
                    || (candidate.bodySnapshotId() != null && !candidate.bodySnapshotId().equals(row.snapshotId()))) {
                continue;
            }
            String actualHash = row.contentHash().strip();
            double similarity = candidate.similarity() != null
                    ? Math.max(0.0d, Math.min(1.0d, candidate.similarity()))
                    : 0.90d;
            String origin = candidate.origin() == null || candidate.origin().isBlank()
                    ? "AGENT_SEED"
                    : candidate.origin().trim();
            verified.add(new EvidenceRef(
                    projectId,
                    row.documentId(),
                    row.chunkId(),
                    row.chunkNo(),
                    row.snapshotId(),
                    actualHash,
                    row.filename(),
                    row.heading(),
                    row.content(),
                    similarity,
                    origin));
        }
        return List.copyOf(verified);
    }

    private java.util.Map<ChunkKey, DbChunkRow> fetchVerifiedChunks(UUID projectId, List<ChunkKey> keys) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("documentIds", keys.stream().map(ChunkKey::documentId).distinct().toList())
                .addValue("chunkNos", keys.stream().map(ChunkKey::chunkNo).distinct().toList());
        List<DbChunkRow> rows = namedJdbc.query("""
                SELECT dc.id AS chunk_id, dc.document_id, dc.chunk_no, b.snapshot_id,
                       pd.original_filename AS filename, dc.heading, dc.content, dc.content_hash
                FROM document_body_chunk dc
                JOIN document_body b ON b.document_id = dc.document_id
                JOIN project_document pd ON pd.id = dc.document_id
                JOIN connector_document_visibility v ON v.id = pd.id AND v.project_id = pd.project_id
                WHERE pd.project_id = :projectId AND pd.status = 'READY' AND v.readable
                  AND dc.document_id IN (:documentIds) AND dc.chunk_no IN (:chunkNos)
                """, params, (rs, rowNum) -> new DbChunkRow(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getInt("chunk_no"),
                rs.getObject("snapshot_id", UUID.class),
                rs.getString("filename"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("content_hash")));
        var result = new LinkedHashMap<ChunkKey, DbChunkRow>();
        for (DbChunkRow row : rows) {
            result.put(new ChunkKey(row.documentId(), row.chunkNo()), row);
        }
        return result;
    }

    private record ChunkKey(UUID documentId, int chunkNo) {
    }

    private record DbChunkRow(UUID chunkId, UUID documentId, int chunkNo, UUID snapshotId,
            String filename, String heading, String content, String contentHash) {
    }
}
