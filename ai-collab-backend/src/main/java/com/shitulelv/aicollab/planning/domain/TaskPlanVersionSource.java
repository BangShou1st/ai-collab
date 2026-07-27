package com.shitulelv.aicollab.planning.domain;

/**
 * Task 7: Source type for plan versions.
 * Tracks how each version was created.
 */
public enum TaskPlanVersionSource {
    AI_SKELETON,
    AI_COMPLETE,
    AI_REPAIR,
    AI_PARTIAL_REPAIR,
    AI_REGENERATED,
    USER_EDIT,
    RESTORED
}
