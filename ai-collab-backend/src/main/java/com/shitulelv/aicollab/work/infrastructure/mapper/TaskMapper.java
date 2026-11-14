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

    /**
     * total 与分页 id 都只扫描 project_task；展示字段只对最多一页的结果回连。
     * 空页仍由 total 侧保留一行，避免越界页额外发起 count 查询。
     */
    @Select("""
            <script>
            WITH filtered AS (
                SELECT t.id, t.created_at
                FROM project_task t
                WHERE t.project_id=#{projectId}
                <if test="status != null">AND t.status=#{status}</if>
                <if test="assigneeId != null">AND t.assignee_id=#{assigneeId}</if>
                <if test="milestoneId != null">AND t.milestone_id=#{milestoneId}</if>
            ), total AS (
                SELECT count(*) AS total_count FROM filtered
            ), page_ids AS (
                SELECT id, created_at
                FROM filtered
                ORDER BY created_at ASC, id ASC
                LIMIT #{limit} OFFSET #{offset}
            )
            SELECT t.*, u.display_name AS assignee_display_name, m.name AS milestone_name,
              (SELECT count(*) FROM task_dependency d
               JOIN project_task required ON required.id=d.depends_on_task_id
               WHERE d.task_id=t.id AND required.project_id=t.project_id
                 AND required.status NOT IN ('DONE','CANCELED')) AS unfinished_dependency_count,
              total.total_count
            FROM total
            LEFT JOIN page_ids p ON TRUE
            LEFT JOIN project_task t ON t.id=p.id
            LEFT JOIN app_user u ON u.id=t.assignee_id
            LEFT JOIN milestone m ON m.id=t.milestone_id AND m.project_id=t.project_id
            ORDER BY p.created_at ASC, p.id ASC
            </script>
            """)
    List<TaskEntity> listPageScoped(
            @Param("projectId") UUID projectId,
            @Param("status") TaskStatus status,
            @Param("assigneeId") UUID assigneeId,
            @Param("milestoneId") UUID milestoneId,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(SELECT_VIEW + " WHERE t.project_id=#{projectId} AND t.id=#{id}")
    Optional<TaskEntity> findScoped(@Param("projectId") UUID projectId, @Param("id") UUID id);

    @Select("SELECT id FROM project_task WHERE project_id=#{projectId}")
    List<UUID> listIds(@Param("projectId") UUID projectId);

    @Select("""
            SELECT id
            FROM project_task
            WHERE project_id=#{projectId}
            ORDER BY id
            FOR UPDATE
            """)
    List<UUID> lockProjectTaskIds(@Param("projectId") UUID projectId);

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

    @Select("""
            <script>
            SELECT d.task_id AS taskId, d.depends_on_task_id AS dependsOnTaskId
            FROM task_dependency d
            JOIN project_task t ON t.id=d.task_id
            JOIN project_task required ON required.id=d.depends_on_task_id
            WHERE t.project_id=#{projectId} AND required.project_id=#{projectId}
              AND d.task_id IN
              <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">
                #{taskId}
              </foreach>
            ORDER BY d.task_id, d.depends_on_task_id
            </script>
            """)
    List<TaskDependencyEdge> listDependencyEdges(
            @Param("projectId") UUID projectId,
            @Param("taskIds") List<UUID> taskIds);

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

    @Select("""
            SELECT t.*, u.display_name AS assignee_display_name, m.name AS milestone_name,
              0 AS unfinished_dependency_count
            FROM project_task t
            LEFT JOIN app_user u ON u.id=t.assignee_id
            LEFT JOIN milestone m ON m.id=t.milestone_id AND m.project_id=t.project_id
            JOIN task_dependency d ON d.task_id=t.id
            WHERE t.project_id=#{projectId} AND d.depends_on_task_id=#{taskId}
            """)
    List<TaskEntity> findDependents(
            @Param("projectId") UUID projectId,
            @Param("taskId") UUID taskId);
}
