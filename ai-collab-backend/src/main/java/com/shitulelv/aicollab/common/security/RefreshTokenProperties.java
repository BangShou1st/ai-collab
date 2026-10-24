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
        @NotNull Boolean cookieSecure,
        @DefaultValue("false") @NotNull Boolean allowInsecureCookie) {

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

    /**
     * 禁用 Cookie 的 Secure 属性属于仅限本地 HTTP 开发的显式让步：必须同时把
     * allow-insecure-cookie 置为 true（环境变量 REFRESH_COOKIE_ALLOW_INSECURE），
     * 否则绑定阶段即拒绝启动，避免生产配置缺失或抄错模板时静默降级为不安全 Cookie。
     * Cookie 安全属性只来自本配置，不读取任何客户端请求头（如 X-Forwarded-Proto）。
     */
    @AssertTrue(message = "cookie-secure=false 会被拒绝启动：仅限本地 HTTP 开发，"
            + "且必须同时设置 security.refresh-token.allow-insecure-cookie=true"
            + "（环境变量 REFRESH_COOKIE_ALLOW_INSECURE=true）显式确认")
    public boolean isInsecureCookieExplicitlyAllowed() {
        return cookieSecure != null && cookieSecure
                || Boolean.TRUE.equals(allowInsecureCookie);
    }
}
