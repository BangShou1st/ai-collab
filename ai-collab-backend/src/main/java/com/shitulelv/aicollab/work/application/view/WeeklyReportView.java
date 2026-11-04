package com.shitulelv.aicollab.work.application.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI 周报确定性统计视图。
 *
 * <p>口径说明（业务正确性修复后的最终定义）：</p>
 * <ul>
 *   <li>快照类字段（taskStats / milestoneStats）：截至报告生成时刻的<b>当前累计状态</b>。</li>
 *   <li>周期类字段（periodStats / 贡献者的 period 字段）：基于可靠事件证据
 *       （project_task.completed_at 完成时间戳、created_at 创建时间戳、audit_log 里程碑审计事件）
 *       还原的<b>本期（startDate 含 ~ endDate 含）变化</b>，不用 status=DONE 或 updated_at 冒充。</li>
 *   <li>本接口是确定性 SQL 统计，不调用大模型；叙述性内容由页面或 Agent 基于这些事实组织。</li>
 * </ul>
 */
public record WeeklyReportView(
        LocalDate reportDate,
        LocalDate startDate,
        LocalDate endDate,
        TaskStatistics taskStats,
        MilestoneStatistics milestoneStats,
        PeriodStatistics periodStats,
        List<TopContributor> topContributors,
        List<String> highlights,
        List<String> risks,
        String dataSource,
        List<String> notes
) {
    /** 任务统计（累计快照，截至报告生成时刻）。 */
    public record TaskStatistics(
            /** 累计任务总数（不含已取消，与 completionRate 分母一致）。 */
            int totalTasks,
            /** 累计已完成任务数（当前 status = DONE）。 */
            int completedTasks,
            /** 当前进行中任务数。 */
            int inProgressTasks,
            /** 当前逾期任务数（due_date 早于今天且未完成未取消）。 */
            int overdueTasks,
            /** 累计完成率 = completedTasks / totalTasks（分母不含已取消）。 */
            BigDecimal completionRate,
            /** 累计已取消任务数（不进入完成率分母）。 */
            int canceledTasks,
            /** 当前活跃任务数（TODO / IN_PROGRESS / BLOCKED 之和）。 */
            int activeTasks,
            /** status=DONE 但缺少 completed_at 完成时间戳的任务数（历史遗留数据，无法参与本期完成统计）。 */
            int completedWithoutCompletedAtCount
    ) {}

    /** 里程碑统计（累计快照，截至报告生成时刻）。 */
    public record MilestoneStatistics(
            int totalMilestones,
            int completedMilestones,
            /** 即将到期里程碑数：target_date 在 [今天, 今天 + dueSoonWindowDays] 窗口内且状态为 PLANNED/ACTIVE；远期里程碑不计入。 */
            int upcomingMilestones,
            int overdueMilestones,
            /** 累计已取消里程碑数。 */
            int canceledMilestones,
            /** 即将到期判定窗口（天），当前为 7。 */
            int dueSoonWindowDays
    ) {}

    /**
     * 本期变化统计（startDate 含 ~ endDate 含），基于可靠事件证据还原；
     * 不用 status=DONE 或 updated_at 冒充完成事件。
     */
    public record PeriodStatistics(
            /** 本期新建任务数（created_at 落在本期，含期内新建后又被取消/完成的任务）。 */
            int createdInPeriod,
            /** 本期完成任务数（completed_at 落在本期且当前 status=DONE）。 */
            int completedInPeriod,
            /** 本期新建里程碑数（audit_log MILESTONE_CREATED 事件还原）。 */
            int milestoneCreatedInPeriod,
            /** 本期完成里程碑数（audit_log MILESTONE_UPDATED 且新状态为 COMPLETED 的事件近似还原；里程碑表无完成时间戳）。 */
            int milestoneCompletedInPeriod
    ) {}

    /** 贡献者统计：区分本期活动与历史累计。 */
    public record TopContributor(
            String displayName,
            /** 历史累计完成任务数。 */
            int completedTasks,
            /** 历史累计负责任务数（含已完成）。 */
            int totalTasks,
            /** 本期完成任务数（completed_at 证据）。 */
            int completedInPeriod,
            /** 本期新建（负责的）任务数（created_at 证据）。 */
            int createdInPeriod
    ) {}
}
