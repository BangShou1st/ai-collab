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
import java.util.Map;
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
        Map<UUID, SeedEvidenceInput> deduped = new LinkedHashMap<>();
        for (SeedEvidenceInput item : candidates) {
            if (item == null || item.documentId() == null || item.chunkId() == null) {
                continue;
            }
            deduped.putIfAbsent(item.chunkId(), item);
            if (deduped.size() >= MAX_SEED_CANDIDATES) {
                break;
            }
        }
        if (deduped.isEmpty()) {
            return List.of();
        }
        Map<UUID, DbChunkRow> rowsByChunk = fetchVerifiedChunks(projectId, List.copyOf(deduped.keySet()));
        List<EvidenceRef> verified = new ArrayList<>();
        for (SeedEvidenceInput candidate : deduped.values()) {
            if (verified.size() >= boundedLimit) {
                break;
            }
            DbChunkRow row = rowsByChunk.get(candidate.chunkId());
            if (row == null || !candidate.documentId().equals(row.documentId())) {
                continue;
            }
            String expectedHash = candidate.contentHash() == null ? null : candidate.contentHash().trim();
            String actualHash = row.contentHash() == null ? "" : row.contentHash().trim();
            if (expectedHash != null && !expectedHash.isBlank() && !expectedHash.equals(actualHash)) {
                continue;
            }
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
                    actualHash,
                    row.filename(),
                    row.heading(),
                    row.content(),
                    similarity,
                    origin));
        }
        return List.copyOf(verified);
    }

    private Map<UUID, DbChunkRow> fetchVerifiedChunks(UUID projectId, List<UUID> chunkIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("chunkIds", chunkIds);
        List<DbChunkRow> rows = namedJdbc.query("""
                SELECT dc.id AS chunk_id,
                       dc.document_id AS document_id,
                       pd.original_filename AS filename,
                       dc.heading AS heading,
                       dc.content AS content,
                       dc.content_hash AS content_hash
                FROM document_chunk dc
                JOIN project_document pd ON pd.id = dc.document_id
                LEFT JOIN connector_document_visibility v
                       ON v.id = pd.id AND v.project_id = pd.project_id
                WHERE pd.project_id = :projectId
                  AND pd.status = 'READY'
                  AND COALESCE(pd.access_blocked, false) = false
                  AND COALESCE(v.readable, true) = true
                  AND dc.id IN (:chunkIds)
                """, params, (rs, rowNum) -> new DbChunkRow(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("filename"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("content_hash")));
        Map<UUID, DbChunkRow> result = new LinkedHashMap<>();
        for (DbChunkRow row : rows) {
            result.putIfAbsent(row.chunkId(), row);
        }
        return result;
    }

    private record DbChunkRow(
            UUID chunkId,
            UUID documentId,
            String filename,
            String heading,
            String content,
            String contentHash) {
    }
}
