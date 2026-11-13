package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一撤销用户全部登录会话。
 *
 * <p>调用方可以在同一事务里先修改密码或状态（这些 SQL 已递增 token_version），
 * 再以 {@code tokenVersionAlreadyIncremented=true} 进入本服务；logoutAll 等没有前置
 * 用户行更新的入口则由本服务递增。所有入口都会撤销 Refresh Token、清理状态缓存并写审计。</p>
 */
@Service
public class UserSessionRevocationService {

    private final UserService users;
    private final RefreshTokenService refreshTokens;
    private final UserSessionStateService sessionStates;
    private final AuditService audit;

    public UserSessionRevocationService(
            UserService users,
            RefreshTokenService refreshTokens,
            UserSessionStateService sessionStates,
            AuditService audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.sessionStates = sessionStates;
        this.audit = audit;
    }

    @Transactional
    public void revokeAllSessions(
            UUID userId,
            RefreshTokenRevokeReason reason,
            String trigger,
            UUID actorId) {
        revokeAllSessions(userId, reason, trigger, actorId, false);
    }

    @Transactional
    public void revokeAllSessions(
            UUID userId,
            RefreshTokenRevokeReason reason,
            String trigger,
            UUID actorId,
            boolean tokenVersionAlreadyIncremented) {
        if (!tokenVersionAlreadyIncremented && !users.bumpTokenVersion(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        refreshTokens.revokeAllForUser(userId, reason);
        evictNowAndAfterCommit(userId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason.name());
        detail.put("trigger", trigger);
        detail.put("tokenVersionIncrementedByCaller", tokenVersionAlreadyIncremented);
        audit.write(null, actorId, "USER_SESSIONS_REVOKED", "USER", userId, detail);
    }

    private void evictNowAndAfterCommit(UUID userId) {
        sessionStates.evict(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sessionStates.evict(userId);
                }
            });
        }
    }
}
