package com.shitulelv.aicollab.retrieval.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的生产级章节正文读取器实现。
 * 通过 JdbcTemplate 查询指定 (projectId, documentId, heading, bodySnapshotId) 下的分块内容，
 * 优先读取完整的 PARENT 块，或按 chunk_no ASC 拼接成完整章节文本，供句子窗口恢复使用。
 */
@Component
public class PostgresDocumentSectionReader implements DocumentSectionReader {

    private static final Logger log = LoggerFactory.getLogger(PostgresDocumentSectionReader.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgresDocumentSectionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> getSectionText(UUID projectId, UUID documentId, String heading, String bodySnapshotId) {
        if (documentId == null) {
            return Optional.empty();
        }

        try {
            // 1. 若提供了 projectId，则进行项目范围访问隔离校验
            if (projectId != null) {
                Integer valid = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM project_document WHERE id = ? AND project_id = ? AND status <> 'DELETING'",
                        Integer.class,
                        documentId,
                        projectId
                );
                if (valid == null || valid == 0) {
                    log.warn("拒绝跨项目读取章节文本: project={}, document={}", projectId, documentId);
                    return Optional.empty();
                }
            }

            // 2. 优先从 document_chunk 中读取真实的 PARENT 块（长章节在分块时完整保存在 PARENT 块中）
            List<String> parentProse;
            if (heading != null && !heading.isBlank()) {
                parentProse = jdbcTemplate.query(
                        "SELECT content FROM document_chunk WHERE document_id = ? AND chunk_type = 'PARENT' AND heading = ? LIMIT 1",
                        (rs, rowNum) -> rs.getString("content"),
                        documentId,
                        heading
                );
            } else {
                parentProse = jdbcTemplate.query(
                        "SELECT content FROM document_chunk WHERE document_id = ? AND chunk_type = 'PARENT' AND (heading IS NULL OR heading = '') LIMIT 1",
                        (rs, rowNum) -> rs.getString("content"),
                        documentId
                );
            }

            if (!parentProse.isEmpty() && parentProse.getFirst() != null && !parentProse.getFirst().isBlank()) {
                return Optional.of(parentProse.getFirst());
            }

            // 3. 尝试从 document_body_chunk 读取并按 chunk_no ASC 拼接章节内容
            List<String> bodyChunks;
            if (bodySnapshotId != null && !bodySnapshotId.isBlank()) {
                UUID snapshotUuid = tryParseUuid(bodySnapshotId);
                if (snapshotUuid != null) {
                    if (heading != null && !heading.isBlank()) {
                        bodyChunks = jdbcTemplate.query(
                                """
                                SELECT c.content FROM document_body_chunk c
                                JOIN document_body b ON b.document_id = c.document_id
                                WHERE c.document_id = ? AND b.snapshot_id = ? AND c.heading = ?
                                ORDER BY c.chunk_no ASC
                                """,
                                (rs, rowNum) -> rs.getString("content"),
                                documentId,
                                snapshotUuid,
                                heading
                        );
                    } else {
                        bodyChunks = jdbcTemplate.query(
                                """
                                SELECT c.content FROM document_body_chunk c
                                JOIN document_body b ON b.document_id = c.document_id
                                WHERE c.document_id = ? AND b.snapshot_id = ? AND (c.heading IS NULL OR c.heading = '')
                                ORDER BY c.chunk_no ASC
                                """,
                                (rs, rowNum) -> rs.getString("content"),
                                documentId,
                                snapshotUuid
                        );
                    }
                } else {
                    bodyChunks = queryBodyChunksByHeading(documentId, heading);
                }
            } else {
                bodyChunks = queryBodyChunksByHeading(documentId, heading);
            }

            if (!bodyChunks.isEmpty()) {
                String stitched = stitchChunks(bodyChunks);
                if (!stitched.isBlank()) {
                    return Optional.of(stitched);
                }
            }

            // 4. 回退从 document_chunk（非 PARENT 块）按 chunk_no ASC 拼接章节内容
            List<String> docChunks;
            if (heading != null && !heading.isBlank()) {
                docChunks = jdbcTemplate.query(
                        """
                        SELECT content FROM document_chunk
                        WHERE document_id = ? AND heading = ? AND (chunk_type IS NULL OR chunk_type <> 'PARENT')
                        ORDER BY chunk_no ASC
                        """,
                        (rs, rowNum) -> rs.getString("content"),
                        documentId,
                        heading
                );
            } else {
                docChunks = jdbcTemplate.query(
                        """
                        SELECT content FROM document_chunk
                        WHERE document_id = ? AND (heading IS NULL OR heading = '') AND (chunk_type IS NULL OR chunk_type <> 'PARENT')
                        ORDER BY chunk_no ASC
                        """,
                        (rs, rowNum) -> rs.getString("content"),
                        documentId
                );
            }

            if (!docChunks.isEmpty()) {
                String stitched = stitchChunks(docChunks);
                if (!stitched.isBlank()) {
                    return Optional.of(stitched);
                }
            }

            return Optional.empty();

        } catch (Exception e) {
            log.warn("读取章节正文异常: project={}, document={}, heading={}, error={}",
                    projectId, documentId, heading, e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> queryBodyChunksByHeading(UUID documentId, String heading) {
        if (heading != null && !heading.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT content FROM document_body_chunk WHERE document_id = ? AND heading = ? ORDER BY chunk_no ASC",
                    (rs, rowNum) -> rs.getString("content"),
                    documentId,
                    heading
            );
        } else {
            return jdbcTemplate.query(
                    "SELECT content FROM document_body_chunk WHERE document_id = ? AND (heading IS NULL OR heading = '') ORDER BY chunk_no ASC",
                    (rs, rowNum) -> rs.getString("content"),
                    documentId
            );
        }
    }

    /**
     * 将多个相邻分块平滑拼接为连贯全文，识别并合并相邻块之间的重叠边界。
     */
    public static String stitchChunks(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        if (contents.size() == 1) {
            return contents.getFirst().trim();
        }

        StringBuilder sb = new StringBuilder();
        for (String chunk : contents) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String trimmed = chunk.trim();
            if (sb.isEmpty()) {
                sb.append(trimmed);
            } else {
                String prev = sb.toString();
                int maxOverlap = Math.min(Math.min(prev.length(), trimmed.length()), 300);
                int overlap = 0;
                for (int len = maxOverlap; len > 0; len--) {
                    if (prev.endsWith(trimmed.substring(0, len))) {
                        overlap = len;
                        break;
                    }
                }
                if (overlap > 0) {
                    sb.append(trimmed.substring(overlap));
                } else {
                    sb.append("\n").append(trimmed);
                }
            }
        }
        return sb.toString();
    }

    private static UUID tryParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
