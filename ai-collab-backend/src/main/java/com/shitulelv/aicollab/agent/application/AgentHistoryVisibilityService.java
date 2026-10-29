package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Old Agent sessions may contain uncited source text in messages, summaries and events. */
@Service
public class AgentHistoryVisibilityService {
    private final JdbcTemplate jdbc;
    public AgentHistoryVisibilityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean visible(UUID projectId, UUID sessionId) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM agent_session session WHERE session.project_id=? AND session.id=?
                  AND NOT EXISTS (SELECT 1 FROM connector_hidden_agent_session h
                    WHERE h.project_id=session.project_id AND h.session_id=session.id AND h.cleared_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM connector_source s LEFT JOIN connector_oauth_authorization a
                    ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                    WHERE s.project_id=session.project_id
                      AND (session.created_at<=GREATEST(s.updated_at,COALESCE(a.updated_at,s.updated_at))
                        OR (a.access_expires_at<=now() AND session.created_at<=a.access_expires_at))
                      AND (s.status<>'ACTIVE' OR (s.user_authorization_id IS NOT NULL
                        AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                  AND NOT EXISTS (SELECT 1 FROM external_document e JOIN connector_source s ON s.id=e.source_id
                    WHERE s.project_id=session.project_id AND e.status='BLOCKED' AND session.created_at<=e.updated_at))
                """, Boolean.class, projectId, sessionId);
        return Boolean.TRUE.equals(value);
    }

    public void requireSession(UUID projectId, UUID sessionId) {
        if (!visible(projectId, sessionId)) throw new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED);
    }

    public void requireRun(UUID projectId, UUID runId) {
        var sessions = jdbc.queryForList("SELECT session_id FROM agent_run WHERE project_id=? AND id=?",
                UUID.class, projectId, runId);
        if (sessions.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        requireSession(projectId, sessions.getFirst());
    }

    public boolean visibleRun(UUID projectId, UUID runId) {
        var sessions = jdbc.queryForList("SELECT session_id FROM agent_run WHERE project_id=? AND id=?",
                UUID.class, projectId, runId);
        return !sessions.isEmpty() && visible(projectId, sessions.getFirst());
    }

    @Transactional
    public void whileRunVisible(UUID projectId, UUID runId, Runnable action) {
        jdbc.queryForList("""
                SELECT a.id FROM connector_oauth_authorization a JOIN connector_source s
                  ON s.user_authorization_id=a.id AND s.project_id=a.project_id
                WHERE s.project_id=? ORDER BY a.id FOR SHARE OF a
                """, UUID.class, projectId);
        jdbc.queryForList("SELECT id FROM connector_source WHERE project_id=? ORDER BY id FOR SHARE", UUID.class, projectId);
        jdbc.queryForList("""
                SELECT d.id FROM project_document d JOIN external_document e ON e.document_id=d.id
                JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
                WHERE d.project_id=? ORDER BY d.id FOR SHARE OF d
                """, UUID.class, projectId);
        requireRun(projectId, runId);
        action.run();
    }
}
