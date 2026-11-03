package com.shitulelv.aicollab.work.application.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 延期风险分析视图：这是<b>规则检测</b>（确定性 SQL 规则命中），不是 AI 预测。
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>已完成（DONE）/已取消（CANCELED）任务不参与任何风险规则；无截止日的任务不参与
 *       逾期 / 即将到期规则（可参与未分配、依赖阻塞规则）。</li>
 *   <li>同一任务可同时命中多条规则（如逾期 + 依赖阻塞）， risks 列表中会显式列出全部风险类型；
 *       summary.affectedTaskCount 按去重任务数统计，summary.riskItemCount 按条目数统计，二者口径不同。</li>
 *   <li>每条风险条目携带 rule 字段给出机器可读的规则依据。</li>
 * </ul>
 */
public record RiskAnalysisView(
        List<RiskItem> risks,
        RiskSummary summary,
        List<RiskRule> rules,
        String dataSource
) {
    public record RiskItem(
            UUID taskId,
            String taskTitle,
            String riskType,
            String severity,
            String description,
            LocalDate dueDate,
            String assigneeName,
            /** 机器可读规则依据（与 rules[].code 对应），如 TASK_OVERDUE / TASK_DUE_SOON / TASK_BLOCKED / TASK_UNASSIGNED。 */
            String rule,
            /** 聚合条目涉及的任务数（如未分配聚合条目）；单任务条目为 null。 */
            Integer affectedTaskCount
    ) {}

    public record RiskSummary(
            int highRiskCount,
            int mediumRiskCount,
            int lowRiskCount,
            String overallAssessment,
            /** 风险条目数（同一任务多条风险会重复出现在条目数中）。 */
            int riskItemCount,
            /** 涉及的去重任务数（同一任务多条风险只计一次；未分配聚合任务计入）。 */
            int affectedTaskCount,
            /** 参与规则检测的活跃任务数（不含已完成 / 已取消）。 */
            int checkedTaskCount
    ) {}

    /** 风险规则的解释性描述，用于前端展示判定依据。 */
    public record RiskRule(
            /** 规则编码，如 TASK_OVERDUE。 */
            String code,
            String name,
            /** 规则判定条件与排除范围说明。 */
            String description
    ) {}
}
