package com.shitulelv.aicollab.auth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 跨所有账号邮件类型按账号限流。Redis SET NX 保证并发请求也只取得一个发送许可。
 */
@Component
public class AccountEmailCooldown {
    private static final Logger log = LoggerFactory.getLogger(AccountEmailCooldown.class);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "rate:account-email:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]); else return 0; end",
            Long.class);
    private static final DefaultRedisScript<Long> MARK_SENT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then "
                    + "return redis.call('EXPIRE',KEYS[1],ARGV[2]); else return 0; end",
            Long.class);

    private final StringRedisTemplate redis;

    public AccountEmailCooldown(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision reserve(UUID accountId) {
        String key = KEY_PREFIX + accountId;
        String token = UUID.randomUUID().toString();
        try {
            Boolean reserved = redis.opsForValue().setIfAbsent(key, token, COOLDOWN);
            if (Boolean.TRUE.equals(reserved)) {
                return Decision.reserved(new Permit(key, token));
            }
            if (reserved == null) {
                return Decision.unavailable();
            }

            Long remaining = redis.getExpire(key, TimeUnit.SECONDS);
            if (remaining == null || remaining < 0) {
                // Key may have expired between SET NX and TTL lookup; try once more atomically.
                reserved = redis.opsForValue().setIfAbsent(key, token, COOLDOWN);
                if (Boolean.TRUE.equals(reserved)) {
                    return Decision.reserved(new Permit(key, token));
                }
                remaining = redis.getExpire(key, TimeUnit.SECONDS);
            }
            return Decision.cooldown(remaining == null || remaining < 1 ? 1 : remaining);
        } catch (RuntimeException exception) {
            // Failing closed keeps the per-account limit consistent across application instances.
            log.warn("Redis 账号邮件冷却不可用，本次跳过发送");
            return Decision.unavailable();
        }
    }

    public void markSent(Permit permit) {
        try {
            redis.execute(MARK_SENT_SCRIPT, List.of(permit.key()), permit.token(),
                    Long.toString(COOLDOWN.getSeconds()));
        } catch (RuntimeException exception) {
            // The reservation already has a 60-second TTL, so send success remains protected.
            log.warn("Redis 账号邮件冷却续期失败");
        }
    }

    public void release(Permit permit) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(permit.key()), permit.token());
        } catch (RuntimeException exception) {
            // The reservation expires automatically even when Redis cannot release it now.
            log.warn("Redis 账号邮件冷却释放失败");
        }
    }

    public record Permit(String key, String token) {}

    public record Decision(Status status, Permit permit, long retryAfterSeconds) {
        public boolean reserved() {
            return status == Status.RESERVED;
        }

        public static Decision reserved(Permit permit) {
            return new Decision(Status.RESERVED, permit, COOLDOWN.getSeconds());
        }

        public static Decision cooldown(long retryAfterSeconds) {
            return new Decision(Status.COOLDOWN, null, retryAfterSeconds);
        }

        public static Decision unavailable() {
            return new Decision(Status.UNAVAILABLE, null, 0);
        }
    }

    public enum Status {
        RESERVED,
        COOLDOWN,
        UNAVAILABLE
    }
}
