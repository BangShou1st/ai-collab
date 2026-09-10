package com.shitulelv.aicollab.auth.ratelimit;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录/注册限流：IP 与用户名双维度，Redis 主限流加进程内 fallback。
 * 客户端 IP 只取 request.getRemoteAddr()，绝不信任 X-Forwarded-For；
 * 生产受信代理场景由 Spring ForwardedHeaderFilter 在框架层解析，限流器看到的仍是解析后地址。
 * 用户名归一化后哈希再拼 key，密码/token/邮箱永不进 key 或日志。
 */
@Component
public class AuthRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);"
                    + "if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;"
                    + "return n;",
            Long.class);

    private final StringRedisTemplate redis;
    private final int loginLimitPerHour;
    private final int registerLimitPerHour;
    private final ConcurrentHashMap<String, AtomicInteger> fallback = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final ZoneId zoneId;

    public AuthRateLimiter(
            StringRedisTemplate redis,
            @Value("${auth.rate-limit.login-per-hour:30}") int loginLimitPerHour,
            @Value("${auth.rate-limit.register-per-hour:10}") int registerLimitPerHour) {
        if (loginLimitPerHour < 1 || registerLimitPerHour < 1) {
            throw new IllegalArgumentException("auth rate limit 必须大于 0");
        }
        this.redis = redis;
        this.loginLimitPerHour = loginLimitPerHour;
        this.registerLimitPerHour = registerLimitPerHour;
        this.zoneId = ZoneId.systemDefault();
    }

    public void checkLogin(HttpServletRequest request, String username) {
        check("rate:auth:login:" + clientIp(request) + ":" + hash(normalize(username)),
                loginLimitPerHour);
    }

    public void checkRegister(HttpServletRequest request) {
        check("rate:auth:register:" + clientIp(request), registerLimitPerHour);
    }

    static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote.strip();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash rate limit key", exception);
        }
    }

    private void check(String prefix, int limit) {
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        String key = prefix + ":" + now.format(WINDOW);
        cleanupFallback(now.format(WINDOW));
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
            log.warn("Redis 认证限流不可用，已切换进程内限流");
            count = localCount;
        }
        if (count > limit) {
            throw new BusinessException(ErrorCode.AUTH_RATE_LIMITED);
        }
    }

    private void cleanupFallback(String currentWindow) {
        if ((cleanupTicker.incrementAndGet() & 255) != 0) return;
        String suffix = ":" + currentWindow;
        fallback.keySet().removeIf(key -> !key.endsWith(suffix));
    }
}
