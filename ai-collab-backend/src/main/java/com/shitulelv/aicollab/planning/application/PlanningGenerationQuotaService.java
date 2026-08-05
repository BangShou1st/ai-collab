package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 服务用于检查用户的成功生成配额。
 *
 * 成功配额语义：
 * - 每个用户在过去 60 分钟内，成功生成并进入 READY 的 AI 规划最多 N 次
 * - 成功定义：创建 AI_COMPLETE、AI_REPAIR、AI_PARTIAL 或 AI_PARTIAL_REPAIR version
 * - 以下不计入成功配额：FAILED、DETAIL_GENERATION_FAILED、CANCELED、DISCARDED、
 *   PLANNING_QUEUE_FULL、PLANNING_MODEL_UNAVAILABLE、PLANNING_MODEL_TIMEOUT、
 *   供应商额度不足、模型输出无效、进程恢复失败
 * - 同一次生成中的 Skeleton、Detail、Repair 只算一个逻辑生成
 * - 手工保存版本不计数
 * - Restore 不计数
 * - Confirm 不计数
 * - 删除已经成功生成的计划不退款
 */
@Service
public class PlanningGenerationQuotaService {
    private static final Logger log = LoggerFactory.getLogger(PlanningGenerationQuotaService.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int generationLimitPerUserHour;

    public PlanningGenerationQuotaService(JdbcTemplate jdbc, Clock clock,
                                          @Value("${planning.generation-limit-per-user-hour:60}") int generationLimitPerUserHour) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.generationLimitPerUserHour = generationLimitPerUserHour;
    }

    /**
     * 检查用户是否还有成功生成配额。
     *
     * @param userId 用户 ID
     * @return true 如果允许生成，false 如果达到限制
     * @throws BusinessException 如果达到限制，抛出 PLANNING_GENERATION_QUOTA_EXCEEDED
     */
    public boolean checkQuota(UUID userId) {
        Instant windowStart = clock.instant().minus(Duration.ofHours(1));

        // 查询过去 60 分钟所有成功 AI 规划/修复版本
        int successCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM ai_task_plan_version
                WHERE created_by = ?
                  AND source_type IN ('AI_COMPLETE','AI_REPAIR','AI_PARTIAL','AI_PARTIAL_REPAIR')
                  AND created_at >= ?
                """, Integer.class, userId, java.sql.Timestamp.from(windowStart));

        // 查询当前活动生成或局部修复
        int activeCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM ai_task_plan p
                JOIN ai_task_plan_attempt a ON a.id = p.active_attempt_id
                WHERE a.created_by = ?
                  AND p.status IN ('SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING')
                  AND a.status IN ('QUEUED', 'RUNNING')
                """, Integer.class, userId);

        int total = successCount + activeCount;

        if (total >= generationLimitPerUserHour) {
            log.warn("User {} has reached generation quota: success={}, active={}, limit={}",
                    userId, successCount, activeCount, generationLimitPerUserHour);
            throw new BusinessException(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED,
                    "过去 60 分钟成功生成的 AI 规划次数已达上限");
        }

        log.debug("User {} quota check: success={}, active={}, limit={}, allowed=true",
                userId, successCount, activeCount, generationLimitPerUserHour);
        return true;
    }

    /**
     * 获取用户当前的成功生成次数。
     *
     * @param userId 用户 ID
     * @return 过去 60 分钟成功生成次数
     */
    public int getSuccessCount(UUID userId) {
        Instant windowStart = clock.instant().minus(Duration.ofHours(1));

        return jdbc.queryForObject("""
                SELECT count(*)
                FROM ai_task_plan_version
                WHERE created_by = ?
                  AND source_type IN ('AI_COMPLETE','AI_REPAIR','AI_PARTIAL','AI_PARTIAL_REPAIR')
                  AND created_at >= ?
                """, Integer.class, userId, java.sql.Timestamp.from(windowStart));
    }

    /**
     * 获取用户当前的活动生成数。
     *
     * @param userId 用户 ID
     * @return 当前活动生成数
     */
    public int getActiveCount(UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM ai_task_plan p
                JOIN ai_task_plan_attempt a ON a.id = p.active_attempt_id
                WHERE a.created_by = ?
                  AND p.status IN ('SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING')
                  AND a.status IN ('QUEUED', 'RUNNING')
                """, Integer.class, userId);
    }
}
