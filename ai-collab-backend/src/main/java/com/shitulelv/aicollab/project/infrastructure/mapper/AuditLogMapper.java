package com.shitulelv.aicollab.project.infrastructure.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface AuditLogMapper {

    /**
     * 保留旧重载：写入空 detail，保持所有历史调用可编译。
     */
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

    /**
     * 新增重载：写入安全结构化 detail（JSON 字符串，由 SQL 转 jsonb）。
     */
    @Insert("""
            INSERT INTO audit_log(id, project_id, user_id, action, entity_type, entity_id, detail, request_id)
            VALUES (#{id}, #{projectId}, #{userId}, #{action}, #{entityType}, #{entityId}, #{detail}::jsonb, #{requestId})
            """)
    int insertWithDetail(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("detail") String detail,
            @Param("requestId") UUID requestId);

    /**
     * 查询列：detail 使用 ::text 显式转为文本，避免 jsonb 无默认 TypeHandler。
     * 注意：AUDIT_COLUMNS 前有一个空格，确保与 SELECT 拼接时不会出现语法错误。
     */
    String AUDIT_COLUMNS = """
             a.id, a.user_id AS "userId", u.display_name AS "userDisplayName",
            a.action, a.entity_type AS "entityType", a.entity_id AS "entityId",
            CASE WHEN a.entity_type='PROJECT_DOCUMENT' AND (
              EXISTS (SELECT 1 FROM connector_document_visibility v
                WHERE v.project_id=a.project_id AND v.id=a.entity_id AND NOT v.readable)
              OR EXISTS (SELECT 1 FROM connector_deletion_journal j
                WHERE j.project_id=a.project_id AND j.document_id=a.entity_id)
            ) THEN '{}' ELSE a.detail::text END AS "detailJson",
            a.request_id AS "requestId", a.created_at AS "createdAt"
            """;

    @Select("""
            SELECT """ + AUDIT_COLUMNS + """
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.user_id
            WHERE a.project_id = #{projectId}
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "userId", javaType = UUID.class),
            @Arg(column = "userDisplayName", javaType = String.class),
            @Arg(column = "action", javaType = String.class),
            @Arg(column = "entityType", javaType = String.class),
            @Arg(column = "entityId", javaType = UUID.class),
            @Arg(column = "detailJson", javaType = String.class),
            @Arg(column = "requestId", javaType = UUID.class),
            @Arg(column = "createdAt", javaType = OffsetDateTime.class)
    })
    List<AuditLogRow> page(
            @Param("projectId") UUID projectId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT count(*) FROM audit_log WHERE project_id = #{projectId}")
    long countByProjectId(@Param("projectId") UUID projectId);

    @Select("""
            SELECT """ + AUDIT_COLUMNS + """
            FROM audit_log a
            LEFT JOIN app_user u ON u.id = a.user_id
            WHERE a.project_id = #{projectId}
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "userId", javaType = UUID.class),
            @Arg(column = "userDisplayName", javaType = String.class),
            @Arg(column = "action", javaType = String.class),
            @Arg(column = "entityType", javaType = String.class),
            @Arg(column = "entityId", javaType = UUID.class),
            @Arg(column = "detailJson", javaType = String.class),
            @Arg(column = "requestId", javaType = UUID.class),
            @Arg(column = "createdAt", javaType = OffsetDateTime.class)
    })
    List<AuditLogRow> recentActivities(
            @Param("projectId") UUID projectId,
            @Param("limit") int limit);
}
