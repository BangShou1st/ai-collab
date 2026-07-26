package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PlanningGenerationRateLimiter {
    private final PlanningModelProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentHashMap<String, AtomicInteger> windows = new ConcurrentHashMap<>();

    public PlanningGenerationRateLimiter(PlanningModelProperties properties) {
        this.properties = properties;
    }

    public void check(UUID userId) {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        String key = userId + ":" + hour;
        windows.keySet().removeIf(existing -> !existing.endsWith(hour.toString()));
        if (windows.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet()
                > properties.generationLimitPerUserHour()) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_RATE_LIMITED, "本小时 AI 规划生成次数已达上限");
        }
    }
}
