package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class KnowledgeRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRateLimiter.class);
    private static final int LIMIT = 30;
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);"
                    + "if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;"
                    + "return n;",
            Long.class);

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, AtomicInteger> fallback = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final ZoneId zoneId;

    public KnowledgeRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.zoneId = ZoneId.systemDefault();
    }

    public void check(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        String window = now.format(WINDOW);
        String key = "rate:qa:" + userId + ":" + window;
        cleanupFallback(window);
        long localCount = fallback.computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        long ttl = Math.max(1L, Duration.between(
                now, now.plusHours(1).truncatedTo(ChronoUnit.HOURS)).toSeconds());
        long count;
        try {
            Long value = redis.execute(SCRIPT, List.of(key), Long.toString(ttl));
            if (value == null) throw new IllegalStateException("Redis 限流返回为空");
            count = Math.max(value, localCount);
        } catch (RuntimeException exception) {
            log.warn("Redis 问答限流不可用，已切换进程内限流");
            count = localCount;
        }
        if (count > LIMIT) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
        }
    }

    private void cleanupFallback(String currentWindow) {
        if ((cleanupTicker.incrementAndGet() & 255) != 0) return;
        String suffix = ":" + currentWindow;
        fallback.keySet().removeIf(key -> !key.endsWith(suffix));
    }
}
