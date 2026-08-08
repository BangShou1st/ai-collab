package com.shitulelv.aicollab.work.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.view.*;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkReportService {
    private final ProjectAccessGuard access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkReportService(ProjectAccessGuard access, JdbcTemplate jdbc, ObjectMapper json) {
        this.access = access;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public WeeklyReportView getWeeklyReport(UUID projectId, UUID userId) {
        return getWeeklyReport(projectId, userId, 7);
    }

    @Transactional(readOnly = true)
    public WeeklyReportView getWeeklyReport(UUID projectId, UUID userId, int days) {
        access.requireMember(projectId, userId);
        java.time.LocalDate since = java.time.LocalDate.now().minusDays(days);

        // 任务统计（限定日期范围）
        var taskStats = jdbc.queryForObject("""
                SELECT
                    count(*) AS totalTasks,
                    count(*) FILTER (WHERE status = 'DONE') AS completedTasks,
                    count(*) FILTER (WHERE status = 'IN_PROGRESS') AS inProgressTasks,
                    count(*) FILTER (WHERE due_date < CURRENT_DATE AND status NOT IN ('DONE','CANCELED')) AS overdueTasks,
                    CASE WHEN count(*) FILTER (WHERE status <> 'CANCELED') = 0 THEN 0.0
                         ELSE round(count(*) FILTER (WHERE status = 'DONE')::numeric
                                    / count(*) FILTER (WHERE status <> 'CANCELED'), 3)
                    END AS completionRate
                FROM project_task WHERE project_id = ?
                  AND (created_at >= ? OR updated_at >= ? OR status = 'DONE')
                """,
                (rs, rowNum) -> new WeeklyReportView.TaskStatistics(
                        rs.getInt("totalTasks"),
                        rs.getInt("completedTasks"),
                        rs.getInt("inProgressTasks"),
                        rs.getInt("overdueTasks"),
                        rs.getBigDecimal("completionRate")
                ),
                projectId, since, since);

        // 里程碑统计（限定日期范围）
        var milestoneStats = jdbc.queryForObject("""
                SELECT
                    count(*) AS totalMilestones,
                    count(*) FILTER (WHERE status = 'COMPLETED') AS completedMilestones,
                    count(*) FILTER (WHERE status IN ('PLANNED','ACTIVE') AND target_date >= CURRENT_DATE) AS upcomingMilestones,
                    count(*) FILTER (WHERE target_date < CURRENT_DATE AND status NOT IN ('COMPLETED','CANCELED')) AS overdueMilestones
                FROM milestone WHERE project_id = ?
                  AND (created_at >= ? OR updated_at >= ? OR status = 'COMPLETED')
                """,
                (rs, rowNum) -> new WeeklyReportView.MilestoneStatistics(
                        rs.getInt("totalMilestones"),
                        rs.getInt("completedMilestones"),
                        rs.getInt("upcomingMilestones"),
                        rs.getInt("overdueMilestones")
                ),
                projectId, since, since);

        // 贡献者排行（限定日期范围）
        List<WeeklyReportView.TopContributor> contributors = jdbc.query("""
                SELECT
                    u.display_name AS displayName,
                    count(*) FILTER (WHERE t.status = 'DONE') AS completedTasks,
                    count(*) AS totalTasks
                FROM project_task t
                JOIN app_user u ON u.id = t.assignee_id
                WHERE t.project_id = ? AND t.assignee_id IS NOT NULL
                  AND (t.created_at >= ? OR t.updated_at >= ? OR t.status = 'DONE')
                GROUP BY t.assignee_id, u.display_name
                ORDER BY completedTasks DESC
                LIMIT 5
                """,
                (rs, rowNum) -> new WeeklyReportView.TopContributor(
                        rs.getString("displayName"),
                        rs.getInt("completedTasks"),
                        rs.getInt("totalTasks")
                ),
                projectId, since, since);

        // 亮点和风险
        List<String> highlights = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        if (taskStats.completionRate() != null && taskStats.completionRate().compareTo(BigDecimal.valueOf(0.7)) >= 0) {
            highlights.add("项目完成率达到 " + taskStats.completionRate().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%");
        }
        if (taskStats.overdueTasks() == 0) {
            highlights.add("没有逾期任务");
        }
        if (milestoneStats.upcomingMilestones() > 0) {
            highlights.add("有 " + milestoneStats.upcomingMilestones() + " 个里程碑即将到期");
        }

        if (taskStats.overdueTasks() > 0) {
            risks.add("有 " + taskStats.overdueTasks() + " 个任务已逾期");
        }
        if (milestoneStats.overdueMilestones() > 0) {
            risks.add("有 " + milestoneStats.overdueMilestones() + " 个里程碑已逾期");
        }
        if (taskStats.inProgressTasks() > taskStats.totalTasks() * 0.5) {
            risks.add("进行中的任务超过一半，可能存在并行度过高的风险");
        }

        return new WeeklyReportView(
                LocalDate.now(),
                taskStats,
                milestoneStats,
                contributors,
                highlights,
                risks
        );
    }

    @Transactional(readOnly = true)
    public RiskAnalysisView getRiskAnalysis(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);

        List<RiskAnalysisView.RiskItem> risks = new ArrayList<>();

        // 逾期任务
        List<Map<String, Object>> overdueTasks = jdbc.queryForList("""
                SELECT t.id, t.title, t.due_date, u.display_name
                FROM project_task t
                LEFT JOIN app_user u ON u.id = t.assignee_id
                WHERE t.project_id = ? AND t.due_date < CURRENT_DATE
                  AND t.status NOT IN ('DONE','CANCELED')
                ORDER BY t.due_date
                """, projectId);

        for (var row : overdueTasks) {
            long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(
                    ((java.sql.Date) row.get("due_date")).toLocalDate(), LocalDate.now());
            risks.add(new RiskAnalysisView.RiskItem(
                    (UUID) row.get("id"),
                    (String) row.get("title"),
                    "OVERDUE",
                    daysOverdue > 7 ? "HIGH" : daysOverdue > 3 ? "MEDIUM" : "LOW",
                    "任务已逾期 " + daysOverdue + " 天",
                    ((java.sql.Date) row.get("due_date")).toLocalDate(),
                    (String) row.get("display_name")
            ));
        }

        // 被阻塞的任务
        List<Map<String, Object>> blockedTasks = jdbc.queryForList("""
                SELECT t.id, t.title, t.due_date, u.display_name,
                       count(d.depends_on_task_id) AS blocked_count
                FROM project_task t
                LEFT JOIN app_user u ON u.id = t.assignee_id
                JOIN task_dependency d ON d.task_id = t.id
                JOIN project_task req ON req.id = d.depends_on_task_id
                WHERE t.project_id = ? AND req.status NOT IN ('DONE','CANCELED')
                  AND t.status NOT IN ('DONE','CANCELED')
                GROUP BY t.id, t.title, t.due_date, u.display_name
                """, projectId);

        for (var row : blockedTasks) {
            risks.add(new RiskAnalysisView.RiskItem(
                    (UUID) row.get("id"),
                    (String) row.get("title"),
                    "BLOCKED",
                    "HIGH",
                    "任务被 " + row.get("blocked_count") + " 个未完成的前置任务阻塞",
                    row.get("due_date") != null ? ((java.sql.Date) row.get("due_date")).toLocalDate() : null,
                    (String) row.get("display_name")
            ));
        }

        // 无负责人的任务
        long unassignedCount = jdbc.queryForObject("""
                SELECT count(*) FROM project_task
                WHERE project_id = ? AND assignee_id IS NULL AND status NOT IN ('DONE','CANCELED')
                """, Long.class, projectId);

        if (unassignedCount > 0) {
            risks.add(new RiskAnalysisView.RiskItem(
                    null,
                    "未分配任务",
                    "UNASSIGNED",
                    "MEDIUM",
                    "有 " + unassignedCount + " 个任务尚未分配负责人",
                    null,
                    null
            ));
        }

        // 风险汇总
        long highCount = risks.stream().filter(r -> "HIGH".equals(r.severity())).count();
        long mediumCount = risks.stream().filter(r -> "MEDIUM".equals(r.severity())).count();
        long lowCount = risks.stream().filter(r -> "LOW".equals(r.severity())).count();

        String assessment;
        if (highCount > 3) {
            assessment = "项目存在较高风险，建议立即关注逾期和阻塞任务";
        } else if (highCount > 0) {
            assessment = "项目存在一定风险，需要关注逾期任务和依赖阻塞";
        } else if (mediumCount > 0) {
            assessment = "项目风险可控，建议及时分配未分配任务";
        } else {
            assessment = "项目进展良好，风险较低";
        }

        return new RiskAnalysisView(
                risks,
                new RiskAnalysisView.RiskSummary(
                        (int) highCount,
                        (int) mediumCount,
                        (int) lowCount,
                        assessment
                )
        );
    }

    @Transactional(readOnly = true)
    public PlanComparisonView getPlanComparison(UUID projectId, UUID planId, UUID userId) {
        access.requireMember(projectId, userId);

        List<Map<String, Object>> planRows = jdbc.queryForList("""
                SELECT p.title, v.tasks_json::text AS tasks_json
                FROM ai_task_plan p
                LEFT JOIN ai_task_plan_version v ON v.id = p.latest_version_id
                WHERE p.id = ? AND p.project_id = ?
                """, planId, projectId);
        if (planRows.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND);
        }
        Map<String, Object> plan = planRows.getFirst();
        List<PlanTask> plannedTasks;
        try {
            String tasksJson = (String) plan.get("tasks_json");
            plannedTasks = tasksJson == null
                    ? List.of()
                    : json.readValue(tasksJson,
                    json.getTypeFactory().constructCollectionType(List.class, PlanTask.class));
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规划版本数据无法读取");
        }

        List<ActualPlanTask> actualTasks = jdbc.query("""
                SELECT t.id, t.source_plan_task_key, t.title, t.priority,
                       t.assignee_id, t.start_date, t.due_date
                FROM project_task t
                WHERE t.source_plan_id = ? AND t.project_id = ?
                """,
                (rs, rowNum) -> new ActualPlanTask(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_plan_task_key"),
                        rs.getString("title"),
                        TaskPriority.valueOf(rs.getString("priority")),
                        rs.getObject("assignee_id", UUID.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class)),
                planId, projectId);

        Map<String, ActualPlanTask> actualByKey = actualTasks.stream()
                .filter(task -> task.sourceKey() != null)
                .collect(Collectors.toMap(ActualPlanTask::sourceKey, task -> task));
        List<PlanComparisonView.TaskComparison> comparisons = new ArrayList<>();
        int matched = 0;
        int modified = 0;
        int missing = 0;
        for (PlanTask planned : plannedTasks) {
            ActualPlanTask actual = actualByKey.remove(planned.tempKey());
            if (actual == null) {
                missing++;
                comparisons.add(new PlanComparisonView.TaskComparison(
                        planned.tempKey(), planned.title(), null, null, "MISSING", List.of()));
                continue;
            }
            List<PlanComparisonView.FieldDiff> diffs = compareFields(planned, actual);
            if (diffs.isEmpty()) {
                matched++;
            } else {
                modified++;
            }
            comparisons.add(new PlanComparisonView.TaskComparison(
                    planned.tempKey(), planned.title(), actual.id(), actual.title(),
                    diffs.isEmpty() ? "MATCHED" : "MODIFIED", diffs));
        }
        for (ActualPlanTask extra : actualByKey.values()) {
            comparisons.add(new PlanComparisonView.TaskComparison(
                    null, null, extra.id(), extra.title(), "EXTRA", List.of()));
        }

        return new PlanComparisonView(
                planId,
                (String) plan.get("title"),
                comparisons,
                new PlanComparisonView.ComparisonSummary(
                        plannedTasks.size(), matched, modified, missing, actualByKey.size()
                )
        );
    }

    private static List<PlanComparisonView.FieldDiff> compareFields(
            PlanTask planned, ActualPlanTask actual) {
        List<PlanComparisonView.FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "title", planned.title(), actual.title());
        addDiff(diffs, "priority", planned.priority(), actual.priority().name());
        addDiff(diffs, "assigneeId", planned.assigneeId(), actual.assigneeId());
        addDiff(diffs, "startDate", planned.startDate(), actual.startDate());
        addDiff(diffs, "dueDate", planned.dueDate(), actual.dueDate());
        return diffs;
    }

    private static void addDiff(List<PlanComparisonView.FieldDiff> diffs,
                                String field, Object planned, Object actual) {
        if (!Objects.equals(planned, actual)) {
            diffs.add(new PlanComparisonView.FieldDiff(
                    field, Objects.toString(planned, ""), Objects.toString(actual, "")));
        }
    }

    private record ActualPlanTask(
            UUID id,
            String sourceKey,
            String title,
            TaskPriority priority,
            UUID assigneeId,
            LocalDate startDate,
            LocalDate dueDate) {
    }
}
