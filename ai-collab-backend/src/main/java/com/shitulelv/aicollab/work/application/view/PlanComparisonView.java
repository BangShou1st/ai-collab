package com.shitulelv.aicollab.work.application.view;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 规划对比视图。
 *
 * <p>基准语义（业务正确性修复后）：</p>
 * <ul>
 *   <li>对比基准是规划的<b>实际应用版本</b>（ai_task_plan.confirmed_version_id，即确认落库时
 *       真正创建任务的版本），而不是可能已被人工编辑过的最新版本。</li>
 *   <li>规划从未确认应用时，实际任务无法可靠关联任何版本 → comparisonStatus = PLAN_NOT_APPLIED，
 *       输出"基准未知/无法对比"，不做逐项匹配判定。</li>
 *   <li>实际任务通过 project_task.source_plan_version_id + source_plan_task_key 与基准版本关联；
 *       无法关联的任务（无来源键 / 来源键重复 / 来自其他版本）单独标注，不强行判定匹配。</li>
 * </ul>
 */
public record PlanComparisonView(
        UUID planId,
        String planName,
        List<TaskComparison> taskComparisons,
        ComparisonSummary summary,
        /** 对比状态：COMPARED = 已按应用版本完成对比；PLAN_NOT_APPLIED = 规划从未应用，无法对比。 */
        String comparisonStatus,
        /** 基准来源：APPLIED_VERSION = 实际应用（确认落库）版本；NONE = 无可用基准。 */
        String baselineSource,
        /** 基准版本 ID（实际应用版本）。 */
        UUID baselineVersionId,
        /** 基准版本号。 */
        Integer baselineVersionNo,
        /** 规划应用（确认）时间；未应用为 null。 */
        OffsetDateTime appliedAt,
        /** 面向用户的口径说明（如"规划从未应用确认，无实际落地基准，无法对比"）。 */
        String message,
        /** 有来源规划但缺 source_plan_task_key、无法关联的实际任务数。 */
        Integer unlinkedTaskCount,
        /** 来源版本不是基准版本的实际任务数（如版本变更后的旧/新版本任务），不参与本次对比。 */
        Integer otherVersionTaskCount,
        /** 来源键重复、无法唯一关联的实际任务数。 */
        Integer ambiguousTaskCount
) {
    public record TaskComparison(
            String plannedTaskId,
            String plannedTitle,
            UUID actualTaskId,
            String actualTitle,
            /** MATCHED / MODIFIED / MISSING / EXTRA / UNLINKED / OTHER_VERSION / AMBIGUOUS。 */
            String status,
            List<FieldDiff> diffs,
            /** 实际任务当前状态（TODO / IN_PROGRESS / BLOCKED / DONE / CANCELED）；无实际任务时为 null。 */
            String actualStatus,
            /** 判定依据说明（如 MISSING 的"未落库或已被删除"、UNLINKED 的原因）。 */
            String note
    ) {}

    public record FieldDiff(
            String fieldName,
            String plannedValue,
            String actualValue
    ) {}

    public record ComparisonSummary(
            int totalPlanned,
            int matchedTasks,
            int modifiedTasks,
            int missingTasks,
            int extraTasks
    ) {}
}
