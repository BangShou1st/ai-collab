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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目洞察报表服务（AI 周报 / 延期风险 / 规划对比）。
 *
 * <p>业务口径（业务正确性专项修复）：</p>
 * <ul>
 *   <li>严格区分累计快照与周期变化。本期"新建"以 project_task.created_at 为证据，
 *       本期"完成"以 project_task.completed_at 为证据（TaskMapper.updateScoped 在每次状态
 *       更新时维护该时间戳），绝不使用 status=DONE 或 updated_at 冒充完成事件。</li>
 *   <li>本服务全部为确定性 SQL 统计，不调用大模型；AI 只能基于这些事实做叙述。</li>
 *   <li>规划对比以实际应用版本（confirmed_version_id）为基准，无法可靠关联时如实输出
 *       "无法对比"，不强行判定匹配。</li>
 * </ul>
 */
@Service
public class WorkReportService {
    /** "即将到期"判定窗口（含首尾共 7 天：[今天, 今天+6]）；远期不纳入。 */
    static final int DUE_SOON_WINDOW_DAYS = 7;
    /** 单次报表允许的最大统计跨度（天）。 */
    private static final int MAX_REPORT_SPAN_DAYS = 62;

    private final ProjectAccessGuard access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkReportService(ProjectAccessGuard access, JdbcTemplate jdbc, ObjectMapper json) {
        this.access = access;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ==================== AI 周报 ====================

    /** 默认统计周期：本周（周一起始，含今天）。 */
    @Transactional(readOnly = true)
    public WeeklyReportView getWeeklyReport(UUID projectId, UUID userId) {
        LocalDate today = LocalDate.now();
        return getWeeklyReport(projectId, userId, today.with(DayOfWeek.MONDAY), today);
    }

    /** 最近 N 天（含今天）口径，供 Agent 周报草稿工具复用。 */
    @Transactional(readOnly = true)
    public WeeklyReportView getWeeklyReport(UUID projectId, UUID userId, int days) {
        LocalDate today = LocalDate.now();
        return getWeeklyReport(projectId, userId, today.minusDays(Math.max(days, 1) - 1L), today);
    }

    /**
     * 显式周期口径：startDate 含、endDate 含；周期变化基于 created_at / completed_at
     * 事件证据还原，快照类字段为截至报告生成时刻的当前累计状态。
     */
    @Transactional(readOnly = true)
    public WeeklyReportView getWeeklyReport(UUID projectId, UUID userId, LocalDate startDate, LocalDate endDate) {
        access.requireMember(projectId, userId);
        if (startDate == null || endDate == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate 与 endDate 不能为空");
        }
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "startDate 不能晚于 endDate");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= MAX_REPORT_SPAN_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "统计周期跨度不能超过 " + MAX_REPORT_SPAN_DAYS + " 天");
        }
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime periodStart = startDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime periodEndExclusive = endDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        int dueSoonEnd = DUE_SOON_WINDOW_DAYS - 1;

        // 任务累计快照 + 本期新建/完成（completed_at / created_at 事件证据）
        var taskStats = jdbc.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE status <> 'CANCELED') AS totalTasks,
                    count(*) FILTER (WHERE status = 'DONE') AS completedTasks,
                    count(*) FILTER (WHERE status = 'IN_PROGRESS') AS inProgressTasks,
                    count(*) FILTER (WHERE status = 'CANCELED') AS canceledTasks,
                    count(*) FILTER (WHERE status IN ('TODO','IN_PROGRESS','BLOCKED')) AS activeTasks,
                    count(*) FILTER (WHERE status = 'DONE' AND completed_at IS NULL) AS doneNoEvidence,
                    count(*) FILTER (WHERE due_date < ? AND status NOT IN ('DONE','CANCELED')) AS overdueTasks,
                    CASE WHEN count(*) FILTER (WHERE status <> 'CANCELED') = 0 THEN 0.0
                         ELSE round(count(*) FILTER (WHERE status = 'DONE')::numeric
                                    / count(*) FILTER (WHERE status <> 'CANCELED'), 3)
                    END AS completionRate
                FROM project_task WHERE project_id = ?
                """,
                (rs, rowNum) -> new WeeklyReportView.TaskStatistics(
                        rs.getInt("totalTasks"),
                        rs.getInt("completedTasks"),
                        rs.getInt("inProgressTasks"),
                        rs.getInt("overdueTasks"),
                        rs.getBigDecimal("completionRate"),
                        rs.getInt("canceledTasks"),
                        rs.getInt("activeTasks"),
                        rs.getInt("doneNoEvidence")
                ),
                today, projectId);

        // 本期周期变化（created_at / completed_at 事件证据）
        var periodCounts = jdbc.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE created_at >= ? AND created_at < ?) AS createdInPeriod,
                    count(*) FILTER (WHERE completed_at >= ? AND completed_at < ? AND status = 'DONE') AS completedInPeriod
                FROM project_task WHERE project_id = ?
                """,
                (rs, rowNum) -> new int[]{rs.getInt("createdInPeriod"), rs.getInt("completedInPeriod")},
                periodStart, periodEndExclusive, periodStart, periodEndExclusive, projectId);
        int createdInPeriod = periodCounts[0];
        int completedInPeriod = periodCounts[1];

        // 里程碑累计快照（upcoming 限定 7 天窗口，远期里程碑不计入）
        var milestoneStats = jdbc.queryForObject("""
                SELECT
                    count(*) AS totalMilestones,
                    count(*) FILTER (WHERE status = 'COMPLETED') AS completedMilestones,
                    count(*) FILTER (WHERE status = 'CANCELED') AS canceledMilestones,
                    count(*) FILTER (WHERE target_date >= ? AND target_date <= ? AND status IN ('PLANNED','ACTIVE')) AS upcomingMilestones,
                    count(*) FILTER (WHERE target_date < ? AND status NOT IN ('COMPLETED','CANCELED')) AS overdueMilestones
                FROM milestone WHERE project_id = ?
                """,
                (rs, rowNum) -> new WeeklyReportView.MilestoneStatistics(
                        rs.getInt("totalMilestones"),
                        rs.getInt("completedMilestones"),
                        rs.getInt("upcomingMilestones"),
                        rs.getInt("overdueMilestones"),
                        rs.getInt("canceledMilestones"),
                        DUE_SOON_WINDOW_DAYS
                ),
                today, today.plusDays(dueSoonEnd), today, projectId);

        // 里程碑本期变化：audit_log 事件近似还原（里程碑表无完成时间戳）
        var milestonePeriod = jdbc.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE action = 'MILESTONE_CREATED') AS createdCnt,
                    count(DISTINCT entity_id) FILTER (WHERE action = 'MILESTONE_UPDATED'
                        AND detail->>'status' = 'COMPLETED' AND m.status = 'COMPLETED') AS completedCnt
                FROM audit_log a
                LEFT JOIN milestone m ON m.id = a.entity_id
                WHERE a.project_id = ? AND a.created_at >= ? AND a.created_at < ?
                  AND a.entity_type = 'MILESTONE'
                """,
                (rs, rowNum) -> new int[]{rs.getInt("createdCnt"), rs.getInt("completedCnt")},
                projectId, periodStart, periodEndExclusive);
        WeeklyReportView.PeriodStatistics period = new WeeklyReportView.PeriodStatistics(
                createdInPeriod,
                completedInPeriod,
                milestonePeriod[0],
                milestonePeriod[1]);

        // 贡献者：仅统计本期有活动（本期新建或本期完成）的成员，区分本期与累计
        List<WeeklyReportView.TopContributor> contributors = jdbc.query("""
                SELECT
                    u.display_name AS displayName,
                    count(*) FILTER (WHERE t.status = 'DONE') AS completedTasks,
                    count(*) AS totalTasks,
                    count(*) FILTER (WHERE t.completed_at >= ? AND t.completed_at < ? AND t.status = 'DONE') AS completedInPeriod,
                    count(*) FILTER (WHERE t.created_at >= ? AND t.created_at < ?) AS createdInPeriod
                FROM project_task t
                JOIN app_user u ON u.id = t.assignee_id
                WHERE t.project_id = ? AND t.assignee_id IS NOT NULL
                GROUP BY t.assignee_id, u.display_name
                HAVING count(*) FILTER (WHERE t.completed_at >= ? AND t.completed_at < ? AND t.status = 'DONE') > 0
                    OR count(*) FILTER (WHERE t.created_at >= ? AND t.created_at < ?) > 0
                ORDER BY completedInPeriod DESC, createdInPeriod DESC, completedTasks DESC
                LIMIT 5
                """,
                (rs, rowNum) -> new WeeklyReportView.TopContributor(
                        rs.getString("displayName"),
                        rs.getInt("completedTasks"),
                        rs.getInt("totalTasks"),
                        rs.getInt("completedInPeriod"),
                        rs.getInt("createdInPeriod")
                ),
                periodStart, periodEndExclusive, periodStart, periodEndExclusive, projectId,
                periodStart, periodEndExclusive, periodStart, periodEndExclusive);

        // 亮点与风险：只基于事实；无数据周期不得输出宣传性文案
        List<String> highlights = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (period.createdInPeriod() > 0) {
            highlights.add("本期新建任务 " + period.createdInPeriod() + " 个");
        }
        if (period.completedInPeriod() > 0) {
            highlights.add("本期完成任务 " + period.completedInPeriod() + " 个");
        }
        if (period.milestoneCreatedInPeriod() > 0) {
            highlights.add("本期新建里程碑 " + period.milestoneCreatedInPeriod() + " 个");
        }
        if (period.milestoneCompletedInPeriod() > 0) {
            highlights.add("本期完成里程碑 " + period.milestoneCompletedInPeriod() + " 个");
        }
        if (taskStats.overdueTasks() == 0 && taskStats.activeTasks() > 0) {
            highlights.add("当前没有逾期任务");
        }
        if (milestoneStats.upcomingMilestones() > 0) {
            highlights.add("未来 " + DUE_SOON_WINDOW_DAYS + " 天内有 " + milestoneStats.upcomingMilestones()
                    + " 个里程碑到期");
        }

        if (taskStats.overdueTasks() > 0) {
            risks.add("有 " + taskStats.overdueTasks() + " 个任务已逾期");
        }
        if (milestoneStats.overdueMilestones() > 0) {
            risks.add("有 " + milestoneStats.overdueMilestones() + " 个里程碑已逾期");
        }
        if (taskStats.totalTasks() > 0
                && taskStats.inProgressTasks() > taskStats.totalTasks() * 0.5) {
            risks.add("进行中的任务超过一半，可能存在并行度过高的风险");
        }
        if (period.createdInPeriod() == 0 && period.completedInPeriod() == 0
                && period.milestoneCreatedInPeriod() == 0 && period.milestoneCompletedInPeriod() == 0) {
            notes.add("统计周期（" + startDate + " ~ " + endDate + "）内没有检测到任务或里程碑的新建/完成事件；"
                    + "以上快照为当前累计状态，不代表本期发生了变化");
        }
        if (taskStats.completedWithoutCompletedAtCount() > 0) {
            notes.add("有 " + taskStats.completedWithoutCompletedAtCount()
                    + " 个已完成任务缺少完成时间戳（历史数据），本期完成统计可能低估");
        }
        notes.add("里程碑无完成时间戳字段，本期里程碑变化基于 audit_log 审计事件近似还原");

        return new WeeklyReportView(
                today,
                startDate,
                endDate,
                taskStats,
                milestoneStats,
                period,
                contributors,
                highlights,
                risks,
                "确定性 SQL 统计：project_task.created_at / completed_at（完成时间戳由任务更新路径维护）"
                        + " + audit_log 审计事件；本接口不调用大模型",
                notes
        );
    }

    // ==================== 延期风险分析（规则检测，非 AI 预测） ====================

    @Transactional(readOnly = true)
    public RiskAnalysisView getRiskAnalysis(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        LocalDate today = LocalDate.now();
        LocalDate dueSoonEnd = today.plusDays(DUE_SOON_WINDOW_DAYS - 1);

        List<RiskAnalysisView.RiskItem> risks = new ArrayList<>();
        Set<UUID> affectedTaskIds = new HashSet<>();

        Long checkedCount = jdbc.queryForObject("""
                SELECT count(*) FROM project_task
                WHERE project_id = ? AND status NOT IN ('DONE','CANCELED')
                """, Long.class, projectId);
        int checkedTaskCount = checkedCount == null ? 0 : checkedCount.intValue();

        // 规则 1/2：逾期与即将到期（互斥：due_date < today 为逾期；[today, today+6] 为即将到期）
        List<Map<String, Object>> dueTasks = jdbc.queryForList("""
                SELECT t.id, t.title, t.due_date, u.display_name
                FROM project_task t
                LEFT JOIN app_user u ON u.id = t.assignee_id
                WHERE t.project_id = ? AND t.status NOT IN ('DONE','CANCELED')
                  AND t.due_date IS NOT NULL AND t.due_date <= ?
                ORDER BY t.due_date
                """, projectId, dueSoonEnd);

        for (var row : dueTasks) {
            UUID taskId = (UUID) row.get("id");
            LocalDate due = ((java.sql.Date) row.get("due_date")).toLocalDate();
            if (due.isBefore(today)) {
                long daysOverdue = ChronoUnit.DAYS.between(due, today);
                risks.add(new RiskAnalysisView.RiskItem(
                        taskId,
                        (String) row.get("title"),
                        "OVERDUE",
                        daysOverdue > 7 ? "HIGH" : daysOverdue > 3 ? "MEDIUM" : "LOW",
                        "任务已逾期 " + daysOverdue + " 天",
                        due,
                        (String) row.get("display_name"),
                        "TASK_OVERDUE",
                        null
                ));
            } else {
                risks.add(new RiskAnalysisView.RiskItem(
                        taskId,
                        (String) row.get("title"),
                        "DUE_SOON",
                        due.isBefore(today.plusDays(3)) ? "MEDIUM" : "LOW",
                        "任务将在 " + DUE_SOON_WINDOW_DAYS + " 天窗口内到期",
                        due,
                        (String) row.get("display_name"),
                        "TASK_DUE_SOON",
                        null
                ));
            }
            affectedTaskIds.add(taskId);
        }

        // 规则 3：依赖阻塞（前置任务未完成/未取消）
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
            UUID taskId = (UUID) row.get("id");
            LocalDate due = row.get("due_date") != null
                    ? ((java.sql.Date) row.get("due_date")).toLocalDate() : null;
            boolean urgent = due != null && !due.isAfter(dueSoonEnd);
            risks.add(new RiskAnalysisView.RiskItem(
                    taskId,
                    (String) row.get("title"),
                    "BLOCKED",
                    urgent ? "HIGH" : "MEDIUM",
                    "任务被 " + row.get("blocked_count") + " 个未完成的前置任务阻塞",
                    due,
                    (String) row.get("display_name"),
                    "TASK_BLOCKED",
                    null
            ));
            affectedTaskIds.add(taskId);
        }

        // 规则 4：未分配负责人（聚合条目，affectedTaskCount 显式给出任务数）
        List<UUID> unassignedIds = jdbc.queryForList("""
                SELECT id FROM project_task
                WHERE project_id = ? AND assignee_id IS NULL AND status NOT IN ('DONE','CANCELED')
                """, UUID.class, projectId);
        int unassignedCount = unassignedIds.size();
        if (unassignedCount > 0) {
            risks.add(new RiskAnalysisView.RiskItem(
                    null,
                    "未分配任务",
                    "UNASSIGNED",
                    "MEDIUM",
                    "有 " + unassignedCount + " 个任务尚未分配负责人",
                    null,
                    null,
                    "TASK_UNASSIGNED",
                    unassignedCount
            ));
        }

        // 汇总：条目数 vs 涉及任务数（同一任务多重风险只计一次；未分配与阻塞等规则命中去重合并）
        long highCount = risks.stream().filter(r -> "HIGH".equals(r.severity())).count();
        long mediumCount = risks.stream().filter(r -> "MEDIUM".equals(r.severity())).count();
        long lowCount = risks.stream().filter(r -> "LOW".equals(r.severity())).count();
        unassignedIds.forEach(affectedTaskIds::add);
        int affectedTaskCount = affectedTaskIds.size();

        String assessment;
        if (checkedTaskCount == 0) {
            assessment = "当前没有活跃任务，未检测到规则命中的风险";
        } else if (highCount > 3) {
            assessment = "项目存在较高风险，建议立即关注逾期和阻塞任务";
        } else if (highCount > 0) {
            assessment = "项目存在一定风险，需要关注逾期任务和依赖阻塞";
        } else if (mediumCount > 0) {
            assessment = "项目风险可控，建议及时处理中等风险条目";
        } else {
            assessment = "未检测到命中风险规则的任务";
        }

        List<RiskAnalysisView.RiskRule> rules = List.of(
                new RiskAnalysisView.RiskRule("TASK_OVERDUE", "逾期",
                        "有截止日、未完成（非 DONE/CANCELED）且截止日早于今天的任务；"
                                + "逾期越久严重度越高（>7 天 HIGH，>3 天 MEDIUM，其余 LOW）"),
                new RiskAnalysisView.RiskRule("TASK_DUE_SOON", "即将到期",
                        "有截止日、未完成且截止日落在 [" + today + ", " + dueSoonEnd + "] 共 "
                                + DUE_SOON_WINDOW_DAYS + " 天窗口内的任务；远期截止日任务不计入"),
                new RiskAnalysisView.RiskRule("TASK_BLOCKED", "依赖阻塞",
                        "存在未完成（非 DONE/CANCELED）前置任务的活跃任务；"
                                + "截止日落在即将到期窗口内或已逾期为 HIGH，否则 MEDIUM"),
                new RiskAnalysisView.RiskRule("TASK_UNASSIGNED", "未分配",
                        "未分配负责人的活跃任务，以聚合条目展示，affectedTaskCount 给出任务数")
        );

        return new RiskAnalysisView(
                risks,
                new RiskAnalysisView.RiskSummary(
                        (int) highCount,
                        (int) mediumCount,
                        (int) lowCount,
                        assessment,
                        risks.size(),
                        affectedTaskCount,
                        checkedTaskCount
                ),
                rules,
                "规则检测（确定性 SQL 规则命中），非 AI 预测；已完成/已取消任务不参与检测，"
                        + "无截止日任务不参与逾期与即将到期规则"
        );
    }

    // ==================== 规划对比（基准 = 实际应用版本） ====================

    @Transactional(readOnly = true)
    public PlanComparisonView getPlanComparison(UUID projectId, UUID planId, UUID userId) {
        access.requireMember(projectId, userId);

        List<Map<String, Object>> planRows = jdbc.queryForList("""
                SELECT p.title, p.status, p.confirmed_version_id, p.confirmed_at,
                       v.tasks_json::text AS tasks_json, v.version_no AS baseline_version_no
                FROM ai_task_plan p
                LEFT JOIN ai_task_plan_version v ON v.id = p.confirmed_version_id
                WHERE p.id = ? AND p.project_id = ?
                """, planId, projectId);
        if (planRows.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND);
        }
        Map<String, Object> plan = planRows.getFirst();

        String planTitle = (String) plan.get("title");
        String planStatus = (String) plan.get("status");
        UUID confirmedVersionId = (UUID) plan.get("confirmed_version_id");
        OffsetDateTime appliedAt = asOffsetDateTime(plan.get("confirmed_at"));
        boolean applied = "CONFIRMED".equals(planStatus) && confirmedVersionId != null;

        // 规划从未应用：无实际落地基准，输出"无法对比"，不做逐项判定
        if (!applied) {
            return new PlanComparisonView(
                    planId,
                    planTitle,
                    List.of(),
                    new PlanComparisonView.ComparisonSummary(0, 0, 0, 0, 0),
                    "PLAN_NOT_APPLIED",
                    "NONE",
                    null,
                    null,
                    appliedAt,
                    "规划从未确认应用（当前状态：" + planStatus + "），没有实际落地任务可对比，基准未知，无法对比",
                    0, 0, 0
            );
        }

        Integer baselineVersionNo = plan.get("baseline_version_no") instanceof Number n ? n.intValue() : null;
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

        List<Map<String, Object>> actualRows = jdbc.queryForList("""
                SELECT t.id, t.source_plan_version_id, t.source_plan_task_key, t.title, t.status,
                       t.priority, t.assignee_id, t.start_date, t.due_date
                FROM project_task t
                WHERE t.source_plan_id = ? AND t.project_id = ?
                """, planId, projectId);

        List<PlanComparisonView.TaskComparison> comparisons = new ArrayList<>();
        List<ActualPlanTask> candidates = new ArrayList<>();
        int unlinkedCount = 0;
        int otherVersionCount = 0;

        for (var row : actualRows) {
            UUID taskId = (UUID) row.get("id");
            UUID versionId = (UUID) row.get("source_plan_version_id");
            String key = (String) row.get("source_plan_task_key");
            ActualPlanTask actual = new ActualPlanTask(
                    taskId,
                    key,
                    (String) row.get("title"),
                    TaskPriority.valueOf((String) row.get("priority")),
                    (UUID) row.get("assignee_id"),
                    row.get("start_date") != null ? ((java.sql.Date) row.get("start_date")).toLocalDate() : null,
                    row.get("due_date") != null ? ((java.sql.Date) row.get("due_date")).toLocalDate() : null,
                    (String) row.get("status"),
                    versionId
            );
            if (key == null) {
                // 无来源键：无法与任何规划条目可靠关联，不强行判定
                unlinkedCount++;
                comparisons.add(new PlanComparisonView.TaskComparison(
                        null, null, taskId, actual.title(), "UNLINKED", List.of(),
                        actual.status(), "任务缺少 source_plan_task_key，无法与规划条目可靠关联，不参与匹配判定"));
            } else if (versionId != null && !versionId.equals(confirmedVersionId)) {
                // 来自其他规划版本（版本变更后的旧任务/新任务），不属于本次基准
                otherVersionCount++;
                comparisons.add(new PlanComparisonView.TaskComparison(
                        null, null, taskId, actual.title(), "OTHER_VERSION", List.of(),
                        actual.status(), "任务来自其他规划版本，不参与本次应用版本基准对比"));
            } else {
                candidates.add(actual);
            }
        }

        // 来源键重复（含版本未知但键相同的多条任务）：无法唯一关联，不强行判定
        Map<String, List<ActualPlanTask>> byKey = candidates.stream()
                .collect(Collectors.groupingBy(ActualPlanTask::sourceKey));
        Map<String, ActualPlanTask> actualByKey = new LinkedHashMap<>();
        Set<String> ambiguousKeys = new HashSet<>();
        int ambiguousCount = 0;
        for (var entry : byKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                ambiguousCount += entry.getValue().size();
                ambiguousKeys.add(entry.getKey());
                for (ActualPlanTask duplicate : entry.getValue()) {
                    comparisons.add(new PlanComparisonView.TaskComparison(
                            entry.getKey(), null, duplicate.id(), duplicate.title(), "AMBIGUOUS", List.of(),
                            duplicate.status(), "来源键重复（" + entry.getValue().size() + " 条任务使用同一键），无法唯一关联，不参与匹配判定"));
                }
            } else {
                actualByKey.put(entry.getKey(), entry.getValue().getFirst());
            }
        }

        int matched = 0;
        int modified = 0;
        int missing = 0;
        for (PlanTask planned : plannedTasks) {
            if (ambiguousKeys.contains(planned.tempKey())) {
                comparisons.add(new PlanComparisonView.TaskComparison(
                        planned.tempKey(), planned.title(), null, null, "AMBIGUOUS", List.of(),
                        null, "存在多条同来源键的实际任务，无法唯一关联，不参与匹配判定"));
                continue;
            }
            ActualPlanTask actual = actualByKey.remove(planned.tempKey());
            if (actual == null) {
                missing++;
                comparisons.add(new PlanComparisonView.TaskComparison(
                        planned.tempKey(), planned.title(), null, null, "MISSING", List.of(),
                        null, "基准版本条目未落库，或对应任务已被删除"));
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
                    diffs.isEmpty() ? "MATCHED" : "MODIFIED", diffs,
                    actual.status(), null));
        }
        // 基准来源键未出现在规划条目中：数据异常，单独列出
        int extra = 0;
        for (ActualPlanTask leftover : actualByKey.values()) {
            extra++;
            comparisons.add(new PlanComparisonView.TaskComparison(
                    leftover.sourceKey(), null, leftover.id(), leftover.title(), "EXTRA", List.of(),
                    leftover.status(), "实际任务来源键未出现在基准版本规划条目中（数据异常）"));
        }

        return new PlanComparisonView(
                planId,
                planTitle,
                comparisons,
                new PlanComparisonView.ComparisonSummary(
                        plannedTasks.size(), matched, modified, missing, extra
                ),
                "COMPARED",
                "APPLIED_VERSION",
                confirmedVersionId,
                baselineVersionNo,
                appliedAt,
                "基准为实际应用版本（确认落库版本" + (baselineVersionNo == null ? "" : " v" + baselineVersionNo) + "），"
                        + "通过 source_plan_version_id + source_plan_task_key 关联",
                unlinkedCount,
                otherVersionCount,
                ambiguousCount
        );
    }

    /** JDBC 对 timestamptz 的返回类型因驱动配置而异，统一转成 OffsetDateTime。 */
    private static OffsetDateTime asOffsetDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime odt -> odt;
            case java.sql.Timestamp ts -> ts.toInstant()
                    .atOffset(ZoneId.systemDefault().getRules().getOffset(ts.toInstant()));
            default -> null;
        };
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
            LocalDate dueDate,
            String status,
            UUID sourceVersionId) {
    }
}
