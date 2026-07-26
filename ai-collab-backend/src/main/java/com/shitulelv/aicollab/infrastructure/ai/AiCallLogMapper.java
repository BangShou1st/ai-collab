package com.shitulelv.aicollab.infrastructure.ai;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface AiCallLogMapper {
    @Insert("""
            INSERT INTO ai_call_log(
              id, user_id, project_id, feature, provider, model, status,
              latency_ms, prompt_tokens, completion_tokens, error_code,
              request_id, created_at)
            VALUES(
              #{id}, #{userId}, #{projectId}, 'KNOWLEDGE_QA', #{provider},
              #{model}, #{status}, #{latencyMs}, #{promptTokens},
              #{completionTokens}, #{errorCode}, #{requestId}, now())
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("status") String status,
            @Param("latencyMs") Long latencyMs,
            @Param("promptTokens") Integer promptTokens,
            @Param("completionTokens") Integer completionTokens,
            @Param("errorCode") String errorCode,
            @Param("requestId") UUID requestId);
}
