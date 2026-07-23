package com.shitulelv.aicollab.project.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface AuditLogMapper {
    @Insert("""
            INSERT INTO audit_log(id, project_id, user_id, action, entity_type, entity_id, detail, request_id)
            VALUES (#{id}, #{projectId}, #{userId}, #{action}, #{entityType}, #{entityId}, '{}'::jsonb, #{requestId})
            """)
    int insert(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("requestId") UUID requestId);
}
