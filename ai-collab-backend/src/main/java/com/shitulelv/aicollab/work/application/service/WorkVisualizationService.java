package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.view.*;
import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.work.infrastructure.repository.MilestoneRepository;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkVisualizationService {
    private final ProjectAccessGuard access;
    private final TaskRepository tasks;
    private final MilestoneRepository milestones;
    private final JdbcTemplate jdbc;

    public WorkVisualizationService(
            ProjectAccessGuard access,
            TaskRepository tasks,
            MilestoneRepository milestones,
            JdbcTemplate jdbc) {
        this.access = access;
        this.tasks = tasks;
        this.milestones = milestones;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public GanttView getGanttData(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);

        List<TaskEntity> taskEntities = tasks.list(projectId, null, null, null);
        List<MilestoneEntity> milestoneEntities = milestones.list(projectId);

        Map<UUID, String> memberNames = loadMemberNames(projectId);

        List<GanttView.GanttTask> ganttTasks = taskEntities.stream()
                .map(t -> new GanttView.GanttTask(
                        t.getId(),
                        t.getTitle(),
                        t.getStatus().name(),
                        memberNames.getOrDefault(t.getAssigneeId(), "未分配"),
                        t.getStartDate(),
                        t.getDueDate(),
                        t.getSortOrder() != null ? t.getSortOrder() : 0
                ))
                .toList();

        List<GanttView.GanttMilestone> ganttMilestones = milestoneEntities.stream()
                .map(m -> new GanttView.GanttMilestone(
                        m.getId(),
                        m.getName(),
                        m.getStatus().name(),
                        m.getTargetDate()
                ))
                .toList();

        List<GanttView.GanttDependency> ganttDependencies = jdbc.query("""
                SELECT task_id, depends_on_task_id FROM task_dependency
                WHERE task_id IN (SELECT id FROM project_task WHERE project_id = ?)
                """,
                (rs, rowNum) -> new GanttView.GanttDependency(
                        UUID.fromString(rs.getString("task_id")),
                        UUID.fromString(rs.getString("depends_on_task_id"))
                ),
                projectId);

        return new GanttView(ganttTasks, ganttMilestones, ganttDependencies);
    }

    @Transactional(readOnly = true)
    public DependencyGraphView getDependencyGraph(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);

        List<TaskEntity> taskEntities = tasks.list(projectId, null, null, null);
        Map<UUID, String> memberNames = loadMemberNames(projectId);

        List<DependencyGraphView.GraphNode> nodes = taskEntities.stream()
                .map(t -> new DependencyGraphView.GraphNode(
                        t.getId(),
                        t.getTitle(),
                        t.getStatus().name(),
                        memberNames.getOrDefault(t.getAssigneeId(), "未分配")
                ))
                .toList();

        List<DependencyGraphView.GraphEdge> edges = jdbc.query("""
                SELECT task_id, depends_on_task_id FROM task_dependency
                WHERE task_id IN (SELECT id FROM project_task WHERE project_id = ?)
                """,
                (rs, rowNum) -> new DependencyGraphView.GraphEdge(
                        UUID.fromString(rs.getString("depends_on_task_id")),
                        UUID.fromString(rs.getString("task_id"))
                ),
                projectId);

        return new DependencyGraphView(nodes, edges);
    }

    @Transactional(readOnly = true)
    public CalendarView getCalendarData(UUID projectId, int year, int month, UUID userId) {
        access.requireMember(projectId, userId);

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startOfMonth = yearMonth.atDay(1);
        LocalDate endOfMonth = yearMonth.atEndOfMonth();

        List<CalendarView.CalendarEvent> events = new ArrayList<>();

        List<TaskEntity> tasksWithDates = jdbc.query("""
                SELECT id, title, status, due_date, start_date FROM project_task
                WHERE project_id = ? AND (due_date BETWEEN ? AND ? OR start_date BETWEEN ? AND ?)
                """,
                (rs, rowNum) -> new TaskEntity() {{
                    setId(UUID.fromString(rs.getString("id")));
                    setTitle(rs.getString("title"));
                    setDueDate(rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null);
                    setStartDate(rs.getDate("start_date") != null ? rs.getDate("start_date").toLocalDate() : null);
                }},
                projectId, startOfMonth, endOfMonth, startOfMonth, endOfMonth);

        for (TaskEntity task : tasksWithDates) {
            if (task.getDueDate() != null) {
                events.add(new CalendarView.CalendarEvent(
                        task.getId(),
                        task.getTitle() + "（截止）",
                        "TASK",
                        task.getDueDate(),
                        "截止"
                ));
            }
            if (task.getStartDate() != null && !task.getStartDate().equals(task.getDueDate())) {
                events.add(new CalendarView.CalendarEvent(
                        task.getId(),
                        task.getTitle() + "（开始）",
                        "TASK",
                        task.getStartDate(),
                        "开始"
                ));
            }
        }

        List<MilestoneEntity> milestonesWithDates = jdbc.query("""
                SELECT id, name, status, target_date FROM milestone
                WHERE project_id = ? AND target_date BETWEEN ? AND ?
                """,
                (rs, rowNum) -> new MilestoneEntity() {{
                    setId(UUID.fromString(rs.getString("id")));
                    setName(rs.getString("name"));
                    setTargetDate(rs.getDate("target_date") != null ? rs.getDate("target_date").toLocalDate() : null);
                }},
                projectId, startOfMonth, endOfMonth);

        for (MilestoneEntity milestone : milestonesWithDates) {
            if (milestone.getTargetDate() != null) {
                events.add(new CalendarView.CalendarEvent(
                        milestone.getId(),
                        milestone.getName() + "（里程碑）",
                        "MILESTONE",
                        milestone.getTargetDate(),
                        "目标"
                ));
            }
        }

        return new CalendarView(events);
    }

    /**
     * 成员负载：基于任务分配与估算工时的当前工作量概览（非实际投入工时，不含容量/超负荷评判）。
     *
     * <p>口径：</p>
     * <ul>
     *   <li>项目全体成员均出现在结果中（含未分配任何任务的成员，当前任务为 0）。</li>
     *   <li>未完成 = TODO/IN_PROGRESS/BLOCKED；已完成（历史）= DONE；CANCELED 仅计入 totalTasks。</li>
     *   <li>逾期单独标注：due_date 早于今天且状态属于未完成。</li>
     *   <li>无预计工时的未完成任务计入 missingEstimateTasks 单独归类，不按 0 混入工时合计。</li>
     *   <li>未分配负责人且未完成的任务不计入任何成员行，单独以 unassignedUncompletedTasks 汇总。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public MemberLoadView getMemberLoad(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);

        List<MemberLoadView.MemberLoad> memberLoads = jdbc.query("""
                SELECT
                    pm.user_id AS userId,
                    u.display_name AS displayName,
                    COALESCE(t.total_tasks, 0) AS totalTasks,
                    COALESCE(t.completed_tasks, 0) AS completedTasks,
                    COALESCE(t.in_progress_tasks, 0) AS inProgressTasks,
                    COALESCE(t.uncompleted_tasks, 0) AS uncompletedTasks,
                    COALESCE(t.overdue_tasks, 0) AS overdueTasks,
                    COALESCE(t.total_estimate_hours, 0) AS totalEstimateHours,
                    COALESCE(t.completed_estimate_hours, 0) AS completedHours,
                    COALESCE(t.completed_estimate_hours, 0) AS completedEstimateHours,
                    COALESCE(t.uncompleted_estimate_hours, 0) AS uncompletedEstimateHours,
                    COALESCE(t.missing_estimate_tasks, 0) AS missingEstimateTasks
                FROM project_member pm
                JOIN app_user u ON u.id = pm.user_id
                LEFT JOIN (
                    SELECT
                        assignee_id,
                        count(*) AS total_tasks,
                        count(*) FILTER (WHERE status = 'DONE') AS completed_tasks,
                        count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress_tasks,
                        count(*) FILTER (WHERE status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')) AS uncompleted_tasks,
                        count(*) FILTER (WHERE due_date < CURRENT_DATE
                                           AND status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')) AS overdue_tasks,
                        COALESCE(sum(estimate_hours), 0) AS total_estimate_hours,
                        COALESCE(sum(CASE WHEN status = 'DONE' THEN estimate_hours END), 0)
                            AS completed_estimate_hours,
                        COALESCE(sum(CASE WHEN status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')
                                          THEN estimate_hours END), 0) AS uncompleted_estimate_hours,
                        count(*) FILTER (WHERE status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')
                                           AND estimate_hours IS NULL) AS missing_estimate_tasks
                    FROM project_task
                    WHERE project_id = ? AND assignee_id IS NOT NULL
                    GROUP BY assignee_id
                ) t ON t.assignee_id = pm.user_id
                WHERE pm.project_id = ?
                ORDER BY COALESCE(t.uncompleted_tasks, 0) DESC,
                         COALESCE(t.total_tasks, 0) DESC,
                         u.display_name
                """,
                (rs, rowNum) -> new MemberLoadView.MemberLoad(
                        UUID.fromString(rs.getString("userId")),
                        rs.getString("displayName"),
                        rs.getInt("totalTasks"),
                        rs.getInt("completedTasks"),
                        rs.getInt("inProgressTasks"),
                        rs.getInt("overdueTasks"),
                        rs.getBigDecimal("totalEstimateHours"),
                        rs.getBigDecimal("completedHours"),
                        rs.getInt("uncompletedTasks"),
                        rs.getBigDecimal("uncompletedEstimateHours"),
                        rs.getBigDecimal("completedEstimateHours"),
                        rs.getInt("missingEstimateTasks")
                ),
                projectId, projectId);

        Integer unassignedUncompleted = jdbc.queryForObject("""
                SELECT count(*)
                FROM project_task
                WHERE project_id = ? AND assignee_id IS NULL
                  AND status IN ('TODO', 'IN_PROGRESS', 'BLOCKED')
                """, Integer.class, projectId);

        return new MemberLoadView(memberLoads, unassignedUncompleted == null ? 0 : unassignedUncompleted);
    }

    private Map<UUID, String> loadMemberNames(UUID projectId) {
        return jdbc.query("""
                SELECT pm.user_id, u.display_name
                FROM project_member pm
                JOIN app_user u ON u.id = pm.user_id
                WHERE pm.project_id = ?
                """,
                (rs, rowNum) -> Map.entry(
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("display_name")
                ),
                projectId
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
