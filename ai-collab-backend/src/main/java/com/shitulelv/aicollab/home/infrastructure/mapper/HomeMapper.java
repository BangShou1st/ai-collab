package com.shitulelv.aicollab.home.infrastructure.mapper;

import com.shitulelv.aicollab.home.application.view.HomeActivityView;
import com.shitulelv.aicollab.home.application.view.HomeProjectView;
import com.shitulelv.aicollab.home.application.view.HomeTaskView;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface HomeMapper {

    @Select("""
            SELECT p.id, p.name, p.status, m.role
            FROM project_member m
            JOIN project p ON p.id = m.project_id
            WHERE m.user_id = #{userId}
            ORDER BY p.updated_at DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "name", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "role", javaType = String.class)
    })
    List<HomeProjectView> recentProjects(@Param("userId") UUID userId, @Param("limit") int limit);

    @Select("""
            SELECT t.id, t.project_id AS projectId, p.name AS projectName, t.title,
                   t.status, t.priority, t.due_date AS dueDate
            FROM project_task t
            JOIN project_member m ON m.project_id = t.project_id AND m.user_id = #{userId}
            JOIN project p ON p.id = t.project_id
            WHERE t.assignee_id = #{userId}
              AND t.status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')
            ORDER BY t.due_date NULLS LAST, t.updated_at DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "projectId", javaType = UUID.class),
        @Arg(column = "projectName", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "priority", javaType = String.class),
        @Arg(column = "dueDate", javaType = java.time.LocalDate.class)
    })
    List<HomeTaskView> myTasks(@Param("userId") UUID userId, @Param("limit") int limit);

    @Select("""
            SELECT count(*)
            FROM agent_approval a
            JOIN project_member m ON m.project_id = a.project_id AND m.user_id = #{userId}
            WHERE a.status = 'PENDING'
            """)
    int pendingApprovals(@Param("userId") UUID userId);

    @Select("""
            SELECT a.id, a.project_id AS projectId, p.name AS projectName,
                   a.action, a.created_at AS createdAt
            FROM audit_log a
            JOIN project_member m ON m.project_id = a.project_id AND m.user_id = #{userId}
            JOIN project p ON p.id = a.project_id
            ORDER BY a.created_at DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, id = true),
        @Arg(column = "projectId", javaType = UUID.class),
        @Arg(column = "projectName", javaType = String.class),
        @Arg(column = "action", javaType = String.class),
        @Arg(column = "createdAt", javaType = java.time.OffsetDateTime.class)
    })
    List<HomeActivityView> recentActivity(@Param("userId") UUID userId, @Param("limit") int limit);
}
