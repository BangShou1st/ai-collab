package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.RefreshTokenProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final RefreshTokenProperties PROPERTIES = new RefreshTokenProperties(
            Duration.ofDays(14), Duration.ofDays(30), "ai_collab_refresh_token", "/api/v1/auth", "Strict", false);

    @Test
    void createsHttpOnlyStrictCookieWhoseMaxAgeDoesNotExceedRemainingLifetime() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
        Instant expiresAt = NOW.plusSeconds(90).plusMillis(999);

        ResponseCookie cookie = service.createCookie("placeholder", expiresAt);

        assertThat(cookie.getName()).isEqualTo(PROPERTIES.cookieName());
        assertThat(cookie.getValue()).isEqualTo("placeholder");
        assertThat(cookie.getPath()).isEqualTo(PROPERTIES.cookiePath());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getSameSite()).isEqualTo(PROPERTIES.sameSite());
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(90));
        assertThat(cookie.getMaxAge()).isLessThanOrEqualTo(Duration.between(NOW, expiresAt));
    }

    @Test
    void clearsCookieWithTheSameSecurityAttributes() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

        ResponseCookie cookie = service.clearCookie();

        assertThat(cookie.getName()).isEqualTo(PROPERTIES.cookieName());
        assertThat(cookie.getPath()).isEqualTo(PROPERTIES.cookiePath());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getSameSite()).isEqualTo(PROPERTIES.sameSite());
        assertThat(cookie.getMaxAge()).isZero();
    }

    @Test
    void usesZeroMaxAgeWhenTheCredentialHasAlreadyExpired() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

        ResponseCookie cookie = service.createCookie("placeholder", NOW.minusSeconds(1));

        assertThat(cookie.getMaxAge()).isZero();
    }

    @Test
    void honorsSecureConfigurationAndNeverSetsDomain() {
        RefreshTokenProperties secureProperties = new RefreshTokenProperties(
                Duration.ofDays(14), Duration.ofDays(30), "ai_collab_refresh_token", "/api/v1/auth", "Strict", true);
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                secureProperties, Clock.fixed(NOW, ZoneOffset.UTC));

        ResponseCookie cookie = service.createCookie("placeholder", NOW.plusSeconds(60));

        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getDomain()).isNull();
    }

    @Test
    void usesTheConfiguredCookieNameForReadingWritingAndClearing() {
        RefreshTokenProperties customProperties = new RefreshTokenProperties(
                Duration.ofDays(14), Duration.ofDays(30), "custom_refresh",
                "/api/v1/auth", "Strict", false);
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                customProperties, Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("ai_collab_refresh_token", "decoy"),
                new Cookie("custom_refresh", "expected"));

        assertThat(service.readCookie(request)).contains("expected");
        assertThat(service.createCookie("placeholder", NOW.plusSeconds(60)).getName())
                .isEqualTo("custom_refresh");
        assertThat(service.clearCookie().getName()).isEqualTo("custom_refresh");
    }
}
