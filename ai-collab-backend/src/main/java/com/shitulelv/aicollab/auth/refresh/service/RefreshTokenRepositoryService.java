package com.shitulelv.aicollab.auth.refresh.service;

import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.mapper.RefreshTokenMapper;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh Token 数据库操作的薄门面，隔离上层会话规则与 MyBatis 查询细节。
 */
@Service
public class RefreshTokenRepositoryService {

    private final RefreshTokenMapper refreshTokenMapper;

    public RefreshTokenRepositoryService(RefreshTokenMapper refreshTokenMapper) {
        this.refreshTokenMapper = refreshTokenMapper;
    }

    public void create(RefreshTokenEntity token) {
        if (refreshTokenMapper.insert(token) != 1) {
            throw new IllegalStateException("Refresh Token 持久化失败");
        }
    }

    public Optional<RefreshTokenEntity> findByTokenHash(String tokenHash) {
        return refreshTokenMapper.selectByTokenHash(tokenHash);
    }

    /** 调用者必须已开启事务；该锁把同一 session 的不同 token 行串行到同一事务队列。 */
    public void lockSession(UUID sessionId) {
        refreshTokenMapper.lockSession(sessionId);
    }

    /**
     * 调用者应在 RefreshTokenService 的事务内使用本方法，事务提交前锁不会释放。
     */
    public Optional<RefreshTokenEntity> findByTokenHashForUpdate(String tokenHash) {
        return refreshTokenMapper.selectByTokenHashForUpdate(tokenHash);
    }

    public int markRotated(UUID tokenId, UUID replacedByTokenId, OffsetDateTime rotatedAt) {
        return refreshTokenMapper.markRotated(tokenId, replacedByTokenId, rotatedAt);
    }

    public int revokeActiveTokensBySessionId(
            UUID sessionId,
            RefreshTokenRevokeReason revokeReason,
            OffsetDateTime revokedAt) {
        return refreshTokenMapper.revokeActiveTokensBySessionId(sessionId, revokeReason, revokedAt);
    }
}
