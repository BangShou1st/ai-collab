package com.shitulelv.aicollab.knowledge.infrastructure.mapper;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface KnowledgeMessageMapper {
    @Select("""
            SELECT m.* FROM knowledge_message m JOIN knowledge_session s ON s.id=m.session_id
            WHERE s.project_id=#{projectId} AND s.id=#{sessionId} AND s.user_id=#{userId}
            AND (m.role='USER' OR (NOT m.insufficient_evidence
                  AND NOT EXISTS (SELECT 1 FROM connector_hidden_answer h WHERE h.project_id=s.project_id AND h.message_id=m.id AND h.cleared_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM connector_source cs LEFT JOIN connector_oauth_authorization a
                    ON a.id=cs.user_authorization_id AND a.project_id=cs.project_id
                    WHERE cs.project_id=s.project_id AND (cs.status<>'ACTIVE' OR (cs.user_authorization_id IS NOT NULL
                      AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
                  AND EXISTS (SELECT 1 FROM knowledge_citation c WHERE c.message_id=m.id)
                  AND NOT EXISTS (SELECT 1 FROM regexp_matches(m.content,'\\[S([0-9]+)\\]','g') AS r(ref)
                      WHERE NOT EXISTS (SELECT 1 FROM knowledge_citation c WHERE c.message_id=m.id AND c.rank::text=r.ref[1]))
                AND NOT EXISTS (SELECT 1 FROM knowledge_citation c JOIN document_chunk k ON k.id=c.chunk_id
                    JOIN project_document d ON d.id=k.document_id WHERE c.message_id=m.id AND (d.status<>'READY' OR d.access_blocked))))
            ORDER BY m.created_at DESC,m.id DESC LIMIT 6
            """)
    List<KnowledgeMessageEntity> recentContext(@Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId, @Param("userId") UUID userId);
    @Insert("""
            INSERT INTO knowledge_message(
              id, session_id, role, content, insufficient_evidence, model_provider,
              model_name, latency_ms, prompt_tokens, completion_tokens, created_at)
            VALUES(
              #{id}, #{sessionId}, #{role}, #{content}, #{insufficientEvidence},
              #{modelProvider}, #{modelName}, #{latencyMs}, #{promptTokens},
              #{completionTokens}, #{createdAt})
            """)
    int insert(KnowledgeMessageEntity entity);

    @Select("""
            SELECT m.id, m.session_id, m.role,
                   CASE WHEN m.role='ASSISTANT' AND (
                     EXISTS (SELECT 1 FROM connector_hidden_answer h WHERE h.project_id=s.project_id AND h.message_id=m.id AND h.cleared_at IS NULL)
                     OR EXISTS (SELECT 1 FROM connector_source cs LEFT JOIN connector_oauth_authorization a
                       ON a.id=cs.user_authorization_id AND a.project_id=cs.project_id
                       WHERE cs.project_id=s.project_id AND (cs.status<>'ACTIVE' OR (cs.user_authorization_id IS NOT NULL
                         AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now())))))
                   THEN '该回答因知识源授权不可用已隐藏。' ELSE m.content END AS content,
                   m.insufficient_evidence,
                   m.model_provider, m.model_name, m.latency_ms, m.prompt_tokens,
                   m.completion_tokens, m.created_at
            FROM knowledge_message m
            JOIN knowledge_session s ON s.id=m.session_id
            WHERE s.project_id=#{projectId} AND s.id=#{sessionId} AND s.user_id=#{userId}
            ORDER BY m.created_at ASC, m.id ASC
            """)
    List<KnowledgeMessageEntity> listOwn(
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId);
}
