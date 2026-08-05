package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KnowledgeRateLimiterTest {

    @Test
    void allowsConfiguredSixtyRequestsAndRejectsTheNextOne() {
        KnowledgeRateLimiter limiter = new KnowledgeRateLimiter(
                mock(StringRedisTemplate.class), 60);
        UUID userId = UUID.randomUUID();

        for (int attempt = 0; attempt < 60; attempt++) {
            limiter.check(userId);
        }

        assertThatThrownBy(() -> limiter.check(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED));
    }

    @Test
    void honorsSmallerConfiguredLimit() {
        KnowledgeRateLimiter limiter = new KnowledgeRateLimiter(
                mock(StringRedisTemplate.class), 2);
        UUID userId = UUID.randomUUID();

        limiter.check(userId);
        limiter.check(userId);

        assertThatThrownBy(() -> limiter.check(userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new KnowledgeRateLimiter(
                mock(StringRedisTemplate.class), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
