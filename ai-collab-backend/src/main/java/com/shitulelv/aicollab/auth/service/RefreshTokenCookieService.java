package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.common.security.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * 统一构造、轮换和清除 Refresh Token Cookie，保持安全属性完全一致。
 * Cookie 的 Max-Age 根据实际过期时刻计算，绝不延长服务端凭据寿命。
 */
@Service
public class RefreshTokenCookieService {

    private final RefreshTokenProperties properties;
    private final Clock clock;

    public RefreshTokenCookieService(RefreshTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseCookie createCookie(String refreshToken, Instant expiresAt) {
        Duration maxAge = remainingLifetime(expiresAt);
        return ResponseCookie.from(properties.cookieName(), refreshToken)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.sameSite())
                .path(properties.cookiePath())
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(properties.cookieName(), "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.sameSite())
                .path(properties.cookiePath())
                .maxAge(Duration.ZERO)
                .build();
    }

    /** 读取与写入、清除共用同一 cookieName 配置，避免环境改名后只改到一侧。 */
    public Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.cookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private Duration remainingLifetime(Instant expiresAt) {
        long remainingSeconds = Duration.between(clock.instant(), expiresAt).toSeconds();
        return Duration.ofSeconds(Math.max(0, remainingSeconds));
    }
}
