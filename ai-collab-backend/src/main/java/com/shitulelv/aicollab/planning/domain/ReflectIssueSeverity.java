package com.shitulelv.aicollab.planning.domain;

/**
 * REFLECT 反思问题严重级别（§5.3）。
 * 与代码校验的 {@link ValidationIssueSeverity}（HARD/BLOCKING_EDITABLE/WARNING）是两套体系：
 * 反思输出只有 HARD/SOFT 两级——HARD 走修复链路，SOFT 落 READY_WITH_ISSUES 供前端展示。
 */
public enum ReflectIssueSeverity {
    /** 硬伤：依赖冲突、里程碑分布失衡等需修复的问题 → 触发补丁修复链路。 */
    HARD,
    /** 软伤：粒度不均、可优化项 → 记录进版本 JSON，不阻塞 READY。 */
    SOFT
}
