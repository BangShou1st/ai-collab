package com.shitulelv.aicollab.document.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Access checks use current durable authorization state, independent of physical cleanup. */
@Repository
public class DocumentVisibilityRepository {
    private final JdbcTemplate jdbc;

    public DocumentVisibilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keep revocation serialized with producing document-specific user-visible metadata. */
    @Transactional
    public boolean lockReadable(UUID projectId, UUID documentId) {
        jdbc.queryForList("""
                SELECT a.id FROM connector_oauth_authorization a JOIN connector_source s
                  ON s.user_authorization_id=a.id AND s.project_id=a.project_id
                JOIN external_document e ON e.source_id=s.id
                WHERE s.project_id=? AND e.document_id=? ORDER BY a.id FOR SHARE OF a
                """,UUID.class,projectId,documentId);
        jdbc.queryForList("""
                SELECT s.id FROM connector_source s JOIN external_document e ON e.source_id=s.id
                WHERE s.project_id=? AND e.document_id=? ORDER BY s.id FOR SHARE OF s
                """,UUID.class,projectId,documentId);
        jdbc.queryForList("SELECT id FROM project_document WHERE project_id=? AND id=? FOR SHARE",
                UUID.class,projectId,documentId);
        return readable(projectId,documentId);
    }

    public boolean readable(UUID projectId, UUID documentId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM connector_document_visibility v
                  WHERE v.project_id=? AND v.id=? AND v.readable)
                """, Boolean.class, projectId, documentId);
        return Boolean.TRUE.equals(result);
    }

    /** All records for a key must be accessible; an ambiguous key fails closed. */
    public boolean readableObject(String objectKey) {
        Boolean result = jdbc.queryForObject("""
                SELECT count(*)>0 AND bool_and(v.readable)
                FROM project_document d JOIN connector_document_visibility v ON v.id=d.id AND v.project_id=d.project_id
                WHERE d.object_key=?
                """, Boolean.class, objectKey);
        return Boolean.TRUE.equals(result);
    }
}
