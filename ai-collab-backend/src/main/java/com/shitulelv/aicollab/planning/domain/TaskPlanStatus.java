package com.shitulelv.aicollab.planning.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TaskPlanStatus {
    SKELETON_GENERATING,
    DETAIL_GENERATING,
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
            case SKELETON_GENERATING -> EnumSet.of(DETAIL_GENERATING, FAILED, CANCELED);
            case DETAIL_GENERATING -> EnumSet.of(READY, READY_WITH_ISSUES, REPAIRING, DETAIL_GENERATION_FAILED, CANCELED);
            case REPAIRING -> EnumSet.of(READY, READY_WITH_ISSUES, FAILED);
            case DETAIL_GENERATION_FAILED -> EnumSet.of(DETAIL_GENERATING, SKELETON_GENERATING);
            case READY -> EnumSet.of(CONFIRMING, SKELETON_GENERATING);
            case READY_WITH_ISSUES -> EnumSet.of(SKELETON_GENERATING);
            case CONFIRMING -> EnumSet.of(CONFIRMED, READY);
            case FAILED, CANCELED -> EnumSet.of(SKELETON_GENERATING);
            case CONFIRMED -> EnumSet.noneOf(TaskPlanStatus.class);
        };
        return allowed.contains(target);
    }

    public boolean isGenerating() {
        return this == SKELETON_GENERATING || this == DETAIL_GENERATING || this == REPAIRING;
    }
}
