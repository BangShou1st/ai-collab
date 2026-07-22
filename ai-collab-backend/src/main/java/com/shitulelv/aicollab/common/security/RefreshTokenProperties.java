package com.shitulelv.aicollab.common.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 集中定义 Refresh Token 的生命周期与浏览器 Cookie 安全属性。
 * 令牌生命周期限制单个凭据的存活时间，Session 生命周期限制滑动续期的绝对上限。
 */
@ConfigurationProperties(prefix = "security.refresh-token")
@Validated
public record RefreshTokenProperties(
        @DefaultValue("14d") @NotNull Duration tokenLifetime,
        @DefaultValue("30d") @NotNull Duration sessionLifetime,
        @DefaultValue("ai_collab_refresh_token") @NotBlank String cookieName,
        @DefaultValue("/api/v1/auth") @NotBlank String cookiePath,
        @DefaultValue("Strict") @NotBlank @Pattern(regexp = "Strict") String sameSite,
        @NotNull Boolean cookieSecure) {

    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofDays(14);
    private static final Duration MAX_SESSION_LIFETIME = Duration.ofDays(30);

    @AssertTrue(message = "Refresh Token 生命周期必须为正数，且 session-lifetime 不得短于 token-lifetime")
    public boolean isLifetimesValid() {
        return sessionLifetime != null
                && tokenLifetime != null
                && !tokenLifetime.isZero()
                && !tokenLifetime.isNegative()
                && !sessionLifetime.isZero()
                && !sessionLifetime.isNegative()
                && tokenLifetime.compareTo(MAX_TOKEN_LIFETIME) <= 0
                && sessionLifetime.compareTo(MAX_SESSION_LIFETIME) <= 0
                && sessionLifetime.compareTo(tokenLifetime) >= 0;
    }
}
