package com.shitulelv.aicollab.planning.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TaskPlanStatus {
    SKELETON_GENERATING,
    /**
     * §5.5 人工检查点：骨架完成后的用户确认状态。
     * 本期（P2）只放开枚举与 DB CHECK，流转不启用——SKELETON_GENERATING → SKELETON_REVIEW
     * 的转移留待检查点功能实装（TODO: skeletonReviewTimeoutMinutes + 确认流接入）。
     */
    SKELETON_REVIEW,
    DETAIL_GENERATING,
    /**
     * §5.3 反思阶段：详情批次全部成功后的模型自评（依赖一致性/粒度均匀度/
     * 里程碑分布/与现有任务冲突）。由 DETAIL_GENERATING 进入，终态为
     * READY / READY_WITH_ISSUES / REPAIRING / FAILED。
     */
    REFLECTING,
    REPAIRING,
    DETAIL_GENERATION_FAILED,
    READY,
    READY_WITH_ISSUES,
    CONFIRMING,
    CONFIRMED,
    FAILED,
    CANCELED;

    public boolean canTransitionTo(TaskPlanStatus target) {
        Set<TaskPlanStatus> allowed = switch (this) {
            // SKELETON_REVIEW 转移本期不启用（TODO §5.5）：启用后 SKELETON_GENERATING → SKELETON_REVIEW，
            // SKELETON_REVIEW → DETAIL_GENERATING / SKELETON_GENERATING / FAILED / CANCELED。
            case SKELETON_GENERATING -> EnumSet.of(DETAIL_GENERATING, FAILED, CANCELED);
            case DETAIL_GENERATING -> EnumSet.of(REFLECTING, READY, READY_WITH_ISSUES, REPAIRING, DETAIL_GENERATION_FAILED, CANCELED);
            case REFLECTING -> EnumSet.of(READY, READY_WITH_ISSUES, REPAIRING, FAILED, CANCELED);
            case REPAIRING -> EnumSet.of(READY, READY_WITH_ISSUES, FAILED);
            case DETAIL_GENERATION_FAILED -> EnumSet.of(DETAIL_GENERATING, SKELETON_GENERATING);
            case READY -> EnumSet.of(CONFIRMING, SKELETON_GENERATING);
            case READY_WITH_ISSUES -> EnumSet.of(SKELETON_GENERATING);
            case CONFIRMING -> EnumSet.of(CONFIRMED, READY);
            case FAILED, CANCELED -> EnumSet.of(SKELETON_GENERATING);
            case CONFIRMED -> EnumSet.noneOf(TaskPlanStatus.class);
            case SKELETON_REVIEW -> EnumSet.noneOf(TaskPlanStatus.class);
        };
        return allowed.contains(target);
    }

    public boolean isGenerating() {
        // REFLECTING 属于生成期（异步 attempt 在跑、可取消、崩溃恢复需回退）；
        // SKELETON_REVIEW 是等待用户的人工检查点，不算生成中。
        return this == SKELETON_GENERATING || this == DETAIL_GENERATING || this == REFLECTING || this == REPAIRING;
    }
}
