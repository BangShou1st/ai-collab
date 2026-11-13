package com.shitulelv.aicollab.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 请求路径上的用户会话状态快照。
 *
 * <p>JWT 只证明签发时的身份；本服务用短 TTL 缓存当前状态与 token_version，
 * 让每个 Bearer 请求都能校验会话是否已被停用、删除或主动撤销。撤销入口必须在同一事务提交后
 * 清掉缓存，单实例本地 demo 因此可以做到下一次请求立即生效。</p>
 */
@Service
public class UserSessionStateService {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    static final long CACHE_MAXIMUM_SIZE = 10_000;

    private final UserService users;
    private final Cache<UUID, UserSessionState> states;

    public UserSessionStateService(UserService users) {
        this.users = users;
        this.states = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .build();
    }

    public Optional<UserSessionState> find(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        UserSessionState cached = states.getIfPresent(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return users.findById(userId).map(this::cache);
    }

    public void evict(UUID userId) {
        if (userId != null) {
            states.invalidate(userId);
        }
    }

    private UserSessionState cache(UserEntity user) {
        UserSessionState state = new UserSessionState(
                user.getStatus() == null ? null : user.getStatus(),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion());
        states.put(user.getId(), state);
        return state;
    }

    public record UserSessionState(UserStatus status, int tokenVersion) {
    }
}
