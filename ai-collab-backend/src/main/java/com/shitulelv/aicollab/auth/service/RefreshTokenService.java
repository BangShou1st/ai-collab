package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.RefreshTokenProperties;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Refresh Token 会话规则的入口。
 *
 * <p>每一次登录都新建 session_id，因此不同浏览器或同一浏览器的重复登录都互不共享撤销范围。</p>
 */
@Service
public class RefreshTokenService {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepositoryService refreshTokenRepositoryService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHashService refreshTokenHashService;
    private final RefreshTokenProperties properties;
    private final UserService userService;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepositoryService refreshTokenRepositoryService,
            RefreshTokenGenerator refreshTokenGenerator,
            RefreshTokenHashService refreshTokenHashService,
            RefreshTokenProperties properties,
            UserService userService,
            Clock clock) {
        this.refreshTokenRepositoryService = refreshTokenRepositoryService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHashService = refreshTokenHashService;
        this.properties = properties;
        this.userService = userService;
        this.clock = clock;
    }

    /**
     * 为一次成功登录签发独立会话。数据库严格只保存 SHA-256 摘要，原文随后只交由 Controller 写入 Cookie。
     */
    @Transactional
    public IssuedRefreshToken createSession(UserEntity user) {
        Instant now = clock.instant();
        Instant tokenExpiresAt = now.plus(properties.tokenLifetime());
        Instant sessionExpiresAt = now.plus(properties.sessionLifetime());
        String refreshToken = refreshTokenGenerator.generate();

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(user.getId());
        entity.setTokenHash(refreshTokenHashService.hash(refreshToken));
        entity.setCreatedAt(OffsetDateTime.ofInstant(now, clock.getZone()));
        entity.setExpiresAt(OffsetDateTime.ofInstant(tokenExpiresAt, clock.getZone()));
        entity.setSessionId(UUID.randomUUID());
        entity.setSessionExpiresAt(OffsetDateTime.ofInstant(sessionExpiresAt, clock.getZone()));
        refreshTokenRepositoryService.create(entity);

        return new IssuedRefreshToken(refreshToken, tokenExpiresAt);
    }

    /**
     * 严格轮换当前凭据。旧记录从加行锁到关联替代记录始终位于同一事务，
     * 因而并发请求只能有一个先完成正常轮换。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RotationResult rotate(String submittedToken) {
        if (submittedToken == null || !TOKEN_FORMAT.matcher(submittedToken).matches()) {
            throw unauthorized();
        }
        Instant nowInstant = clock.instant();
        OffsetDateTime now = OffsetDateTime.ofInstant(nowInstant, clock.getZone());
        String tokenHash = refreshTokenHashService.hash(submittedToken);
        RefreshTokenEntity located = refreshTokenRepositoryService.findByTokenHash(tokenHash)
                .orElseThrow(RefreshTokenService::unauthorized);
        /*
         * 固定锁顺序：先无锁定位 session，再拿 session 事务锁，最后重新锁定并校验 token 行。
         * 不同代 token 因共享 session 锁而串行；未知 token 不持有 advisory lock。
         */
        refreshTokenRepositoryService.lockSession(located.getSessionId());
        RefreshTokenEntity current = refreshTokenRepositoryService
                .findByTokenHashForUpdate(tokenHash)
                .orElseThrow(RefreshTokenService::unauthorized);
        if (!current.getSessionId().equals(located.getSessionId())) {
            throw unauthorized();
        }
        if (current.getRevokedAt() != null) {
            if (current.getRevokeReason() == RefreshTokenRevokeReason.ROTATED) {
                // 已轮换凭据再次出现意味着原文可能泄露，只隔离它所属的设备会话。
                refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                        current.getSessionId(), RefreshTokenRevokeReason.REUSE_DETECTED, now);
            }
            throw unauthorized();
        }
        if (!current.getExpiresAt().toInstant().isAfter(nowInstant)
                || !current.getSessionExpiresAt().toInstant().isAfter(nowInstant)) {
            refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                    current.getSessionId(), RefreshTokenRevokeReason.EXPIRED, now);
            throw unauthorized();
        }
        UserEntity user = userService.findById(current.getUserId()).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                    current.getSessionId(), RefreshTokenRevokeReason.USER_UNAVAILABLE, now);
            throw unauthorized();
        }

        Instant expiresAt = min(
                nowInstant.plus(properties.tokenLifetime()),
                current.getSessionExpiresAt().toInstant());
        String replacementValue = refreshTokenGenerator.generate();
        RefreshTokenEntity replacement = new RefreshTokenEntity();
        replacement.setId(UUID.randomUUID());
        replacement.setUserId(current.getUserId());
        replacement.setTokenHash(refreshTokenHashService.hash(replacementValue));
        replacement.setExpiresAt(OffsetDateTime.ofInstant(expiresAt, clock.getZone()));
        replacement.setCreatedAt(now);
        replacement.setSessionId(current.getSessionId());
        replacement.setSessionExpiresAt(current.getSessionExpiresAt());
        refreshTokenRepositoryService.create(replacement);
        if (refreshTokenRepositoryService.markRotated(current.getId(), replacement.getId(), now) != 1) {
            throw new IllegalStateException("Refresh Token 轮换状态更新失败");
        }
        return new RotationResult(user, new IssuedRefreshToken(replacementValue, expiresAt));
    }

    /**
     * 撤销当前浏览器提交凭据所属的会话。无效、未知或已撤销凭据都不暴露差异，保持幂等成功。
     * 必须沿用轮换的锁顺序，避免登出与轮换并发时把旧定位结果用于错误的会话。
     */
    @Transactional
    public void revokeSession(String submittedToken) {
        if (submittedToken == null || !TOKEN_FORMAT.matcher(submittedToken).matches()) {
            return;
        }
        String tokenHash = refreshTokenHashService.hash(submittedToken);
        RefreshTokenEntity located = refreshTokenRepositoryService.findByTokenHash(tokenHash).orElse(null);
        if (located == null) {
            return;
        }
        refreshTokenRepositoryService.lockSession(located.getSessionId());
        RefreshTokenEntity current = refreshTokenRepositoryService.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (current == null || !current.getSessionId().equals(located.getSessionId())) {
            return;
        }
        if (current.getRevokedAt() != null
                && current.getRevokeReason() != RefreshTokenRevokeReason.ROTATED) {
            return;
        }
        Instant nowInstant = clock.instant();
        if (current.getRevokedAt() == null
                && (!current.getExpiresAt().toInstant().isAfter(nowInstant)
                || !current.getSessionExpiresAt().toInstant().isAfter(nowInstant))) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(nowInstant, clock.getZone());
        refreshTokenRepositoryService.revokeActiveTokensBySessionId(
                current.getSessionId(), RefreshTokenRevokeReason.LOGOUT, now);
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepositoryService.revokeActiveTokensByUserId(
                userId, OffsetDateTime.ofInstant(clock.instant(), clock.getZone()));
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
    }

    /** 轮换后的内部结果只在业务调用链中短暂存在，不会直接序列化。 */
    public record RotationResult(UserEntity user, IssuedRefreshToken refreshToken) {
    }
}
