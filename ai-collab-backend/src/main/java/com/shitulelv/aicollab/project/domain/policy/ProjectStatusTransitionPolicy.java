package com.shitulelv.aicollab.project.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 项目状态转换策略。
 *
 * 合法转换：
 * - PREPARING → ACTIVE（开始项目）
 * - ACTIVE → COMPLETED（完成项目）
 * - ACTIVE → ARCHIVED（归档进行中项目）
 * - COMPLETED → ARCHIVED（归档已完成项目）
 * - COMPLETED → ACTIVE（重新激活，如果需要）
 * - ARCHIVED → ACTIVE（重新激活已归档项目）
 *
 * 不允许：
 * - PREPARING → COMPLETED（跳过进行中）
 * - PREPARING → ARCHIVED（跳过进行中）
 * - ARCHIVED → COMPLETED（先激活再完成）
 * - ARCHIVED → PREPARING（不允许回退到准备中）
 */
public final class ProjectStatusTransitionPolicy {

    private static final Map<ProjectStatus, Set<ProjectStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ProjectStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(ProjectStatus.PREPARING, EnumSet.of(ProjectStatus.ACTIVE));
        ALLOWED_TRANSITIONS.put(ProjectStatus.ACTIVE, EnumSet.of(ProjectStatus.COMPLETED, ProjectStatus.ARCHIVED));
        ALLOWED_TRANSITIONS.put(ProjectStatus.COMPLETED, EnumSet.of(ProjectStatus.ACTIVE, ProjectStatus.ARCHIVED));
        ALLOWED_TRANSITIONS.put(ProjectStatus.ARCHIVED, EnumSet.of(ProjectStatus.ACTIVE));
    }

    private ProjectStatusTransitionPolicy() {
    }

    /**
     * 验证状态转换是否合法。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @throws BusinessException 如果转换不合法
     */
    public static void validateTransition(ProjectStatus current, ProjectStatus target) {
        if (current == target) {
            return; // 同一状态幂等
        }
        Set<ProjectStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    String.format("项目状态不能从 %s 转换为 %s", current, target));
        }
    }

    /**
     * 检查状态转换是否合法。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @return true 如果转换合法
     */
    public static boolean isTransitionValid(ProjectStatus current, ProjectStatus target) {
        if (current == target) {
            return true;
        }
        Set<ProjectStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }
}
