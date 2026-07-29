package com.shitulelv.aicollab.project.infrastructure.mapper;

import com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView;
import com.shitulelv.aicollab.project.application.view.DashboardProjectView;
import com.shitulelv.aicollab.project.application.view.DashboardRecentDocumentView;
import com.shitulelv.aicollab.project.application.view.DashboardRecentTaskView;
import com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectDashboardMapper {

    @Select("""
            SELECT p.id, p.name, p.description, p.status, p.start_date, p.due_date,
                   pm.role, (SELECT count(*) FROM project_member WHERE project_id = #{projectId}) AS memberCount
            FROM project p
            JOIN project_member pm ON pm.project_id = p.id AND pm.user_id = #{userId}
            WHERE p.id = #{projectId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "status", javaType = ProjectStatus.class),
            @Arg(column = "start_date", javaType = LocalDate.class),
            @Arg(column = "due_date", javaType = LocalDate.class),
            @Arg(column = "role", javaType = ProjectRole.class),
            @Arg(column = "memberCount", javaType = Long.class)
    })
    DashboardProjectView findProjectInfo(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select("""
            SELECT
              count(*) AS total,
              count(*) FILTER (WHERE status = 'TODO') AS todo,
              count(*) FILTER (WHERE status = 'IN_PROGRESS') AS inProgress,
              count(*) FILTER (WHERE status = 'BLOCKED') AS blocked,
              count(*) FILTER (WHERE status = 'DONE') AS done,
              count(*) FILTER (WHERE status = 'CANCELED') AS canceled,
              count(*) FILTER (WHERE due_date < CURRENT_DATE AND status NOT IN ('DONE','CANCELED')) AS overdue,
              CASE WHEN count(*) FILTER (WHERE status <> 'CANCELED') = 0 THEN 0.0
                   ELSE round(count(*) FILTER (WHERE status = 'DONE')::numeric
                              / count(*) FILTER (WHERE status <> 'CANCELED'), 3)
              END AS completionRate
            FROM project_task
            WHERE project_id = #{projectId}
            """)
    @ConstructorArgs({
            @Arg(column = "total", javaType = int.class),
            @Arg(column = "todo", javaType = int.class),
            @Arg(column = "inProgress", javaType = int.class),
            @Arg(column = "blocked", javaType = int.class),
            @Arg(column = "done", javaType = int.class),
            @Arg(column = "canceled", javaType = int.class),
            @Arg(column = "overdue", javaType = int.class),
            @Arg(column = "completionRate", javaType = double.class)
    })
    DashboardTaskStatsView countTasksByStatus(@Param("projectId") UUID projectId);

    @Select("""
            SELECT m.id, m.name, m.status, m.target_date,
              COALESCE(task_stats.total, 0) AS totalTasks,
              COALESCE(task_stats.done, 0) AS completedTasks,
              CASE WHEN COALESCE(task_stats.total, 0) - COALESCE(task_stats.canceled, 0) = 0 THEN 0.0
                   ELSE round(COALESCE(task_stats.done, 0)::numeric
                              / (COALESCE(task_stats.total, 0) - COALESCE(task_stats.canceled, 0)), 3)
              END AS completionRate,
              CASE WHEN m.target_date < CURRENT_DATE AND m.status NOT IN ('COMPLETED','CANCELED') THEN true
                   ELSE false
              END AS overdue
            FROM milestone m
            LEFT JOIN (
              SELECT milestone_id,
                     count(*) AS total,
                     count(*) FILTER (WHERE status = 'DONE') AS done,
                     count(*) FILTER (WHERE status = 'CANCELED') AS canceled
              FROM project_task
              WHERE project_id = #{projectId} AND milestone_id IS NOT NULL
              GROUP BY milestone_id
            ) task_stats ON task_stats.milestone_id = m.id
            WHERE m.project_id = #{projectId}
            ORDER BY m.sort_order, m.created_at
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "status", javaType = MilestoneStatus.class),
            @Arg(column = "target_date", javaType = LocalDate.class),
            @Arg(column = "totalTasks", javaType = int.class),
            @Arg(column = "completedTasks", javaType = int.class),
            @Arg(column = "completionRate", javaType = double.class),
            @Arg(column = "overdue", javaType = boolean.class)
    })
    List<DashboardMilestoneProgressView> listMilestoneProgress(@Param("projectId") UUID projectId);

    @Select("""
            SELECT t.id, t.title, t.status, t.priority,
                   t.assignee_id AS assigneeId, u.display_name AS assigneeDisplayName,
                   t.milestone_id AS milestoneId, ms.name AS milestoneName,
                   t.due_date AS dueDate,
                   (SELECT count(*) FROM task_dependency d
                    JOIN project_task req ON req.id = d.depends_on_task_id
                    WHERE d.task_id = t.id AND req.project_id = t.project_id
                      AND req.status NOT IN ('DONE','CANCELED')) AS unfinishedDependencyCount,
                   CASE WHEN t.due_date < CURRENT_DATE AND t.status NOT IN ('DONE','CANCELED') THEN true
                        ELSE false
                   END AS overdue,
                   t.updated_at AS updatedAt
            FROM project_task t
            LEFT JOIN app_user u ON u.id = t.assignee_id
            LEFT JOIN milestone ms ON ms.id = t.milestone_id AND ms.project_id = t.project_id
            WHERE t.project_id = #{projectId}
            ORDER BY t.updated_at DESC, t.id DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "status", javaType = TaskStatus.class),
            @Arg(column = "priority", javaType = TaskPriority.class),
            @Arg(column = "assigneeId", javaType = UUID.class),
            @Arg(column = "assigneeDisplayName", javaType = String.class),
            @Arg(column = "milestoneId", javaType = UUID.class),
            @Arg(column = "milestoneName", javaType = String.class),
            @Arg(column = "dueDate", javaType = LocalDate.class),
            @Arg(column = "unfinishedDependencyCount", javaType = int.class),
            @Arg(column = "overdue", javaType = boolean.class),
            @Arg(column = "updatedAt", javaType = OffsetDateTime.class)
    })
    List<DashboardRecentTaskView> listRecentTasks(@Param("projectId") UUID projectId, @Param("limit") int limit);

    @Select("""
            SELECT d.id, d.original_filename AS originalFilename, d.status,
                   d.uploaded_by AS uploadedBy, u.display_name AS uploaderDisplayName,
                   d.created_at AS createdAt
            FROM project_document d
            LEFT JOIN app_user u ON u.id = d.uploaded_by
            WHERE d.project_id = #{projectId}
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "originalFilename", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "uploadedBy", javaType = UUID.class),
            @Arg(column = "uploaderDisplayName", javaType = String.class),
            @Arg(column = "createdAt", javaType = OffsetDateTime.class)
    })
    List<DashboardRecentDocumentView> listRecentDocuments(@Param("projectId") UUID projectId, @Param("limit") int limit);
}
