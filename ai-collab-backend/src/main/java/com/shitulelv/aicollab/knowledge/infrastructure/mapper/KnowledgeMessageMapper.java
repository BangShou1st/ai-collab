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
            SELECT m.id, m.session_id, m.role, m.content, m.insufficient_evidence,
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
