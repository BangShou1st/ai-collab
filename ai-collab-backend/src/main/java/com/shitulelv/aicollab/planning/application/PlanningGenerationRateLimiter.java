package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class PlanningGenerationRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(PlanningGenerationRateLimiter.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);"
                    + "if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;"
                    + "return n;",
            Long.class);

    private final PlanningModelProperties properties;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, AtomicInteger> fallback = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger();

    public PlanningGenerationRateLimiter(PlanningModelProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    public void check(UUID userId) {
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        String key = "rate:planning:" + userId + ":" + hour;
        cleanupFallback(hour);
        long localCount = fallback.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        long ttl = Math.max(1L, Duration.between(Instant.now(), hour.plusSeconds(3600)).toSeconds());
        long count;
        try {
            Long value = redis.execute(SCRIPT, List.of(key), Long.toString(ttl));
            if (value == null) throw new IllegalStateException("Redis 限流返回为空");
            count = Math.max(value, localCount);
        } catch (RuntimeException exception) {
            log.warn("Redis 规划限流不可用，已切换进程内限流");
            count = localCount;
        }
        if (count > properties.generationLimitPerUserHour()) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_RATE_LIMITED, "本小时 AI 规划生成次数已达上限");
        }
    }

    private void cleanupFallback(Instant currentHour) {
        if ((cleanupTicker.incrementAndGet() & 255) != 0) return;
        String suffix = ":" + currentHour;
        fallback.keySet().removeIf(k -> !k.endsWith(suffix));
    }
}
