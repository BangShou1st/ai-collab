package com.shitulelv.aicollab.work.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.work.domain.model.TaskDependencyEdge;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    String SELECT_VIEW = """
            SELECT t.*, u.display_name AS assignee_display_name, m.name AS milestone_name,
              (SELECT count(*) FROM task_dependency d
               JOIN project_task required ON required.id=d.depends_on_task_id
               WHERE d.task_id=t.id AND required.project_id=t.project_id
                 AND required.status NOT IN ('DONE','CANCELED')) AS unfinished_dependency_count
            FROM project_task t
            LEFT JOIN app_user u ON u.id=t.assignee_id
            LEFT JOIN milestone m ON m.id=t.milestone_id AND m.project_id=t.project_id
            """;

    @Select("""
            <script>
            """ + SELECT_VIEW + """
            WHERE t.project_id=#{projectId}
            <if test="status != null">AND t.status=#{status}</if>
            <if test="assigneeId != null">AND t.assignee_id=#{assigneeId}</if>
            <if test="milestoneId != null">AND t.milestone_id=#{milestoneId}</if>
            ORDER BY t.created_at
            </script>
            """)
    List<TaskEntity> listScoped(
            @Param("projectId") UUID projectId,
            @Param("status") TaskStatus status,
            @Param("assigneeId") UUID assigneeId,
            @Param("milestoneId") UUID milestoneId);

    @Select(SELECT_VIEW + " WHERE t.project_id=#{projectId} AND t.id=#{id}")
    Optional<TaskEntity> findScoped(@Param("projectId") UUID projectId, @Param("id") UUID id);

    @Select("SELECT id FROM project_task WHERE project_id=#{projectId}")
    List<UUID> listIds(@Param("projectId") UUID projectId);

    @Update("""
            UPDATE project_task SET title=#{item.title}, description=#{item.description},
              milestone_id=#{item.milestoneId}, assignee_id=#{item.assigneeId},
              status=#{item.status}, priority=#{item.priority}, estimate_hours=#{item.estimateHours},
              start_date=#{item.startDate}, due_date=#{item.dueDate},
              completed_at=CASE WHEN #{item.status}='DONE' THEN COALESCE(completed_at, now()) ELSE NULL END,
              version=version+1, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{item.id} AND version=#{item.version}
            """)
    int updateScoped(@Param("projectId") UUID projectId, @Param("item") TaskEntity item);

    @Delete("DELETE FROM project_task WHERE project_id=#{projectId} AND id=#{id}")
    int deleteScoped(@Param("projectId") UUID projectId, @Param("id") UUID id);

    @Select("""
            SELECT d.task_id AS taskId, d.depends_on_task_id AS dependsOnTaskId
            FROM task_dependency d
            JOIN project_task t ON t.id=d.task_id
            JOIN project_task required ON required.id=d.depends_on_task_id
            WHERE t.project_id=#{projectId} AND required.project_id=#{projectId}
            """)
    List<TaskDependencyEdge> listEdges(@Param("projectId") UUID projectId);

    @Select("""
            SELECT d.depends_on_task_id FROM task_dependency d
            JOIN project_task t ON t.id=d.task_id
            JOIN project_task required ON required.id=d.depends_on_task_id
            WHERE t.project_id=#{projectId} AND required.project_id=#{projectId} AND d.task_id=#{taskId}
            ORDER BY d.depends_on_task_id
            """)
    List<UUID> listDependencyIds(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    @Delete("""
            DELETE FROM task_dependency d USING project_task t
            WHERE d.task_id=t.id AND t.project_id=#{projectId} AND d.task_id=#{taskId}
            """)
    int deleteDependencies(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    @Insert("""
            INSERT INTO task_dependency(task_id, depends_on_task_id)
            SELECT #{taskId}, #{dependencyId}
            FROM project_task t, project_task d
            WHERE t.id=#{taskId} AND d.id=#{dependencyId}
              AND t.project_id=#{projectId} AND d.project_id=#{projectId}
            """)
    int insertDependency(
            @Param("projectId") UUID projectId,
            @Param("taskId") UUID taskId,
            @Param("dependencyId") UUID dependencyId);
}
