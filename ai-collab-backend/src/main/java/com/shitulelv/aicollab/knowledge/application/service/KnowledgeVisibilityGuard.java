package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Holds short read locks while emitting output so revocation cannot commit during an emission. */
@Service
public class KnowledgeVisibilityGuard {
    private final JdbcTemplate jdbc;
    private final DocumentVisibilityRepository documents;

    public KnowledgeVisibilityGuard(JdbcTemplate jdbc, DocumentVisibilityRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Transactional
    public void whileVisible(UUID projectId, List<KnowledgeSource> sources, Runnable emit) {
        jdbc.queryForList("""
                SELECT a.id FROM connector_oauth_authorization a JOIN connector_source s
                  ON s.user_authorization_id=a.id AND s.project_id=a.project_id
                WHERE s.project_id=? ORDER BY a.id FOR SHARE OF a
                """, UUID.class, projectId);
        jdbc.queryForList("SELECT id FROM connector_source WHERE project_id=? ORDER BY id FOR SHARE",
                UUID.class, projectId);
        jdbc.queryForList("""
                SELECT d.id FROM project_document d JOIN external_document e ON e.document_id=d.id
                JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
                WHERE d.project_id=? ORDER BY d.id FOR SHARE OF d
                """, UUID.class, projectId);
        Boolean anyBlocked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM connector_source s LEFT JOIN connector_oauth_authorization a
                  ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                  WHERE s.project_id=? AND (s.status<>'ACTIVE' OR (s.user_authorization_id IS NOT NULL
                    AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                  OR EXISTS (SELECT 1 FROM external_document e JOIN connector_source s ON s.id=e.source_id
                    WHERE s.project_id=? AND e.status='BLOCKED')
                """, Boolean.class, projectId, projectId);
        if (Boolean.TRUE.equals(anyBlocked)
                || sources.stream().map(KnowledgeSource::documentId).distinct().anyMatch(id -> !documents.readable(projectId, id))) {
            throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
        }
        emit.run();
    }
}
