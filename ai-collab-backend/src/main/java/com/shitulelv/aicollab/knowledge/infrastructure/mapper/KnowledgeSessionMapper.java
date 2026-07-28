package com.shitulelv.aicollab.knowledge.infrastructure.mapper;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface KnowledgeSessionMapper {
    @Insert("""
            INSERT INTO knowledge_session(id, project_id, user_id, title, created_at, updated_at)
            VALUES(#{id}, #{projectId}, #{userId}, #{title}, #{createdAt}, #{updatedAt})
            """)
    int insert(KnowledgeSessionEntity entity);

    @Update("""
            UPDATE knowledge_session SET title=#{title}, updated_at=#{updatedAt}
            WHERE id=#{id} AND project_id=#{projectId} AND user_id=#{userId}
            """)
    int update(KnowledgeSessionEntity entity);

    @Select("""
            SELECT id, project_id, user_id, title, created_at, updated_at
            FROM knowledge_session
            WHERE project_id=#{projectId} AND user_id=#{userId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<KnowledgeSessionEntity> listOwn(@Param("projectId") UUID projectId,
                                        @Param("userId") UUID userId);

    @Select("""
            SELECT id, project_id, user_id, title, created_at, updated_at
            FROM knowledge_session
            WHERE project_id=#{projectId} AND id=#{sessionId} AND user_id=#{userId}
            """)
    Optional<KnowledgeSessionEntity> findOwn(
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId);

    @Select("""
            SELECT id, project_id, user_id, title, created_at, updated_at
            FROM knowledge_session
            WHERE project_id=#{projectId} AND id=#{sessionId} AND user_id=#{userId}
            FOR UPDATE
            """)
    Optional<KnowledgeSessionEntity> lockOwn(
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId);

    @Delete("""
            DELETE FROM knowledge_session
            WHERE project_id=#{projectId} AND id=#{sessionId} AND user_id=#{userId}
            """)
    int deleteOwn(@Param("projectId") UUID projectId,
                  @Param("sessionId") UUID sessionId,
                  @Param("userId") UUID userId);

    @Update("""
            UPDATE knowledge_session SET updated_at=#{updatedAt}
            WHERE project_id=#{projectId} AND id=#{sessionId} AND user_id=#{userId}
            """)
    int touch(@Param("projectId") UUID projectId,
              @Param("sessionId") UUID sessionId,
              @Param("userId") UUID userId,
              @Param("updatedAt") OffsetDateTime updatedAt);
}
