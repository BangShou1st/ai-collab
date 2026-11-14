package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionRevocationServiceTest {

    private UserService users;
    private RefreshTokenService refreshTokens;
    private UserSessionStateService sessionStates;
    private AuditService audit;
    private UserSessionRevocationService service;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        refreshTokens = mock(RefreshTokenService.class);
        sessionStates = mock(UserSessionStateService.class);
        audit = mock(AuditService.class);
        service = new UserSessionRevocationService(users, refreshTokens, sessionStates, audit);
    }

    @Test
    void revoke_all_increments_version_revokes_refresh_evicts_and_audits() {
        UUID userId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(users.bumpTokenVersion(userId)).thenReturn(true);

        service.revokeAllSessions(userId, RefreshTokenRevokeReason.LOGOUT, "LOGOUT_ALL", actorId);

        verify(users).bumpTokenVersion(userId);
        verify(refreshTokens).revokeAllForUser(userId, RefreshTokenRevokeReason.LOGOUT);
        verify(sessionStates).evict(userId);
        verify(audit).write(eq(null), eq(actorId), eq("USER_SESSIONS_REVOKED"), eq("USER"), eq(userId), any());
    }

    @Test
    void caller_that_already_bumped_version_is_not_bumped_twice() {
        UUID userId = UUID.randomUUID();
        when(users.bumpTokenVersion(userId)).thenReturn(true);

        service.revokeAllSessions(
                userId, RefreshTokenRevokeReason.USER_UNAVAILABLE, "USER_DISABLED", userId, true);

        verify(users, never()).bumpTokenVersion(userId);
        verify(refreshTokens).revokeAllForUser(userId, RefreshTokenRevokeReason.USER_UNAVAILABLE);
        verify(sessionStates).evict(userId);
    }

    @Test
    void missing_user_fails_before_refresh_revocation() {
        UUID userId = UUID.randomUUID();
        when(users.bumpTokenVersion(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.revokeAllSessions(
                userId, RefreshTokenRevokeReason.LOGOUT, "LOGOUT_ALL", userId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        verify(refreshTokens, never()).revokeAllForUser(any(), any());
    }
}
