package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.RefreshTokenProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh Token Cookie 的浏览器侧安全属性回归：
 * HttpOnly / SameSite / Secure / Path / Max-Age 必须与配置严格一致，
 * Secure 属性只来自服务端配置，任何环境都不应降级或泄露令牌到非 Cookie 通道之外。
 */
class RefreshTokenCookieServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RefreshTokenProperties properties(boolean cookieSecure) {
        return new RefreshTokenProperties(
                Duration.ofDays(14), Duration.ofDays(30),
                "ai_collab_refresh_token", "/api/v1/auth",
                "Strict", cookieSecure, !cookieSecure);
    }

    @Test
    void productionCookie_isSecureHttpOnlySameSiteStrict() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties(true), clock);

        var cookie = service.createCookie("token-value", NOW.plus(Duration.ofDays(14)));

        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getName()).isEqualTo("ai_collab_refresh_token");
    }

    @Test
    void localHttpCookie_keepsHttpOnlyAndSameSite_withoutSecure() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties(false), clock);

        var cookie = service.createCookie("token-value", NOW.plus(Duration.ofDays(14)));

        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    @Test
    void maxAge_isRemainingLifetime_notFullLifetime() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties(true), clock);

        // 距过期仅剩 1 小时：Cookie 只能存活剩余时间，不得借发 Cookie 延长服务端凭据寿命。
        var cookie = service.createCookie("token-value", NOW.plus(Duration.ofHours(1)));

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void alreadyExpiredCookie_neverHasNegativeMaxAge() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties(true), clock);

        var cookie = service.createCookie("token-value", NOW.minusSeconds(60));

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void clearCookie_emptiesValueAndKeepsSameAttributes() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties(true), clock);

        var cookie = service.clearCookie();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    }
}
