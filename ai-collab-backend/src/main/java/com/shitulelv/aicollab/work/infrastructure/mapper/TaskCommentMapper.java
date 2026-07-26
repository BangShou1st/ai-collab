package com.shitulelv.aicollab.work.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskCommentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TaskCommentMapper extends BaseMapper<TaskCommentEntity> {
    String SELECT_VIEW = """
            SELECT c.*, u.display_name AS author_display_name
            FROM task_comment c
            JOIN project_task t ON t.id=c.task_id AND t.project_id=c.project_id
            JOIN app_user u ON u.id=c.author_id
            """;

    @Select(SELECT_VIEW + """
            WHERE c.project_id=#{projectId} AND c.task_id=#{taskId}
            ORDER BY c.created_at
            """)
    List<TaskCommentEntity> listScoped(
            @Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    @Select(SELECT_VIEW + """
            WHERE c.project_id=#{projectId} AND c.task_id=#{taskId} AND c.id=#{commentId}
            """)
    Optional<TaskCommentEntity> findScoped(
            @Param("projectId") UUID projectId,
            @Param("taskId") UUID taskId,
            @Param("commentId") UUID commentId);

    @Update("""
            UPDATE task_comment SET content=#{content}, updated_at=now()
            WHERE project_id=#{projectId} AND task_id=#{taskId} AND id=#{commentId} AND author_id=#{authorId}
            """)
    int updateByAuthor(
            @Param("projectId") UUID projectId, @Param("taskId") UUID taskId,
            @Param("commentId") UUID commentId, @Param("authorId") UUID authorId,
            @Param("content") String content);

    @Delete("""
            DELETE FROM task_comment
            WHERE project_id=#{projectId} AND task_id=#{taskId} AND id=#{commentId}
            """)
    int deleteScoped(
            @Param("projectId") UUID projectId,
            @Param("taskId") UUID taskId,
            @Param("commentId") UUID commentId);
}
