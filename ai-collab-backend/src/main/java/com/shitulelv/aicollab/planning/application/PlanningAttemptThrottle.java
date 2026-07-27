package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 短时防刷限流服务。
 *
 * 语义：防止用户在短时间内疯狂点击，错误码是 PLANNING_ATTEMPT_RATE_LIMITED，
 * 而不是 PLANNING_GENERATION_QUOTA_EXCEEDED。
 *
 * 实现：
 * - Redis 正常时只使用 Redis
 * - 只有 Redis 调用失败时才使用本地 fallback
 * - 不再执行 max(redis, local)
 * - 错误码与成功配额不同
 * - 文案与供应商额度不同
 */
@Component
public class PlanningAttemptThrottle {
    private static final Logger log = LoggerFactory.getLogger(PlanningAttemptThrottle.class);

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);"
                    + "if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;"
                    + "return n;",
            Long.class);

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, AtomicInteger> localFallback = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final int attemptLimitPerUserMinute;

    public PlanningAttemptThrottle(StringRedisTemplate redis,
                                   @Value("${planning.attempt-limit-per-user-minute:15}") int attemptLimitPerUserMinute) {
        this.redis = redis;
        this.attemptLimitPerUserMinute = attemptLimitPerUserMinute;
    }

    /**
     * 检查用户操作是否过于频繁。
     *
     * @param userId 用户 ID
     * @throws BusinessException 如果操作过于频繁，抛出 PLANNING_ATTEMPT_RATE_LIMITED
     */
    public void check(UUID userId) {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        String key = "throttle:planning:" + userId + ":" + minute;

        cleanupLocalFallback(minute);

        // 优先使用 Redis
        try {
            Long value = redis.execute(SCRIPT, List.of(key), Long.toString(Duration.ofMinutes(1).toSeconds()));
            if (value == null) throw new IllegalStateException("Redis 限流返回为空");

            if (value > attemptLimitPerUserMinute) {
                log.warn("User {} has exceeded attempt throttle: count={}, limit={}",
                        userId, value, attemptLimitPerUserMinute);
                throw new BusinessException(ErrorCode.PLANNING_ATTEMPT_RATE_LIMITED,
                        "操作过于频繁，请稍后再试");
            }
            return;
        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (RuntimeException e) {
            // Redis 不可用，使用本地 fallback
            log.warn("Redis 规划限流不可用，已切换进程内限流");
        }

        // Redis 不可用时使用本地 fallback
        long localCount = localFallback.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();

        if (localCount > attemptLimitPerUserMinute) {
            log.warn("User {} has exceeded attempt throttle (local fallback): count={}, limit={}",
                    userId, localCount, attemptLimitPerUserMinute);
            throw new BusinessException(ErrorCode.PLANNING_ATTEMPT_RATE_LIMITED,
                    "操作过于频繁，请稍后再试");
        }
    }

    private void cleanupLocalFallback(Instant currentMinute) {
        if ((cleanupTicker.incrementAndGet() & 255) != 0) return;
        String suffix = ":" + currentMinute;
        localFallback.keySet().removeIf(k -> !k.endsWith(suffix));
    }
}
