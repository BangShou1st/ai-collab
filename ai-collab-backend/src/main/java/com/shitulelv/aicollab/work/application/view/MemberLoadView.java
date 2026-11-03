package com.shitulelv.aicollab.work.application.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 成员负载视图：基于任务分配与估算工时的<b>当前工作量概览</b>。
 *
 * <p>口径说明（重要，前端展示必须遵循）：</p>
 * <ul>
 *   <li>所有金额类字段均为任务的<b>估算工时</b>（estimate_hours），不是实际投入工时；
 *       系统未记录实际工时，任何视图不得将其解释为实际投入。</li>
 *   <li>项目成员即使没有任何分配任务也会出现在 {@link #members} 中（当前任务为 0），
 *       不代表其可承接任意工作量——本视图不含容量数据，禁止输出"超负荷"结论或负荷评分。</li>
 *   <li>无预计工时的未完成任务计入 {@link MemberLoad#missingEstimateTasks()} 单独归类，
 *       不按 0 混入任何工时合计。</li>
 * </ul>
 *
 * @param members                      项目全体成员的当前负载（含未分配任何任务的成员）
 * @param unassignedUncompletedTasks   本项目中"未完成（TODO/IN_PROGRESS/BLOCKED）且未分配负责人"的任务数
 */
public record MemberLoadView(
        List<MemberLoad> members,
        int unassignedUncompletedTasks
) {
    /**
     * 单个成员的当前负载。
     *
     * <p>状态分组口径：未完成 = TODO / IN_PROGRESS / BLOCKED；已完成 = DONE（历史）；
     * CANCELED 不计入任何未完成或已完成指标，仅计入 totalTasks。</p>
     *
     * @param userId                    成员用户 ID
     * @param displayName               成员显示名
     * @param totalTasks                该成员在本项目中被分配的任务总数（含 DONE / CANCELED）
     * @param completedTasks            已完成任务数（status = DONE，历史累计）
     * @param inProgressTasks           进行中任务数（status = IN_PROGRESS）
     * @param overdueTasks              逾期且未完成任务数（due_date 早于今天且 status ∈ 未完成状态，单独标注）
     * @param totalEstimateHours        累计估算工时：本项目全部已分配任务的 estimate_hours 之和（含已完成历史任务；无估算任务不计入）
     * @param completedHours            已完成任务累计估算工时（历史字段，与 completedEstimateHours 同值，仅为兼容保留，新代码请用 completedEstimateHours）
     * @param uncompletedTasks          当前未完成任务数（status ∈ TODO / IN_PROGRESS / BLOCKED）
     * @param uncompletedEstimateHours  未完成估算工时：当前未完成任务的 estimate_hours 之和（当前负担口径；无估算任务不计入，另见 missingEstimateTasks）
     * @param completedEstimateHours    已完成任务的估算工时之和（历史累计估算口径）
     * @param missingEstimateTasks      当前未完成任务中无预计工时（estimate_hours IS NULL）的数量，单独归类，不按 0 计入工时合计
     */
    public record MemberLoad(
            UUID userId,
            String displayName,
            int totalTasks,
            int completedTasks,
            int inProgressTasks,
            int overdueTasks,
            BigDecimal totalEstimateHours,
            BigDecimal completedHours,
            int uncompletedTasks,
            BigDecimal uncompletedEstimateHours,
            BigDecimal completedEstimateHours,
            int missingEstimateTasks
    ) {}
}
