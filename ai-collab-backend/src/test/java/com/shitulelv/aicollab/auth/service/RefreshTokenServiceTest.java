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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Mock
    private RefreshTokenRepositoryService refreshTokenRepositoryService;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private UserService userService;

    @Test
    void createsIndependentSessionsWithExactFixedClockExpiryBoundaries() {
        RefreshTokenProperties properties = new RefreshTokenProperties(
                Duration.ofDays(14), Duration.ofDays(30), "ai_collab_refresh_token", "/api/v1/auth", "Strict", false);
        when(refreshTokenGenerator.generate()).thenReturn("first-token", "second-token");
        RefreshTokenService service = new RefreshTokenService(
                refreshTokenRepositoryService,
                refreshTokenGenerator,
                new RefreshTokenHashService(),
                properties,
                userService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());

        IssuedRefreshToken first = service.createSession(user);
        IssuedRefreshToken second = service.createSession(user);

        ArgumentCaptor<RefreshTokenEntity> entities = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepositoryService, times(2)).create(entities.capture());
        List<RefreshTokenEntity> created = entities.getAllValues();
        OffsetDateTime expectedTokenExpiry = OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(14)), ZoneOffset.UTC);
        OffsetDateTime expectedSessionExpiry = OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC);
        assertThat(first.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(second.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(created).allSatisfy(entity -> {
            assertThat(entity.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(entity.getExpiresAt()).isEqualTo(expectedTokenExpiry);
            assertThat(entity.getSessionExpiresAt()).isEqualTo(expectedSessionExpiry);
        });
        assertThat(created.get(0).getSessionId()).isNotEqualTo(created.get(1).getSessionId());
    }

    @Test
    void rotatesIntoTheSameSessionAndCapsNewExpiryAtTheAbsoluteSessionDeadline() {
        RefreshTokenProperties properties = properties();
        String submittedToken = "A".repeat(43);
        String replacementToken = "B".repeat(43);
        UserEntity user = activeUser();
        UUID oldTokenId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime sessionExpiresAt = OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC);
        RefreshTokenEntity oldToken = activeToken(oldTokenId, user.getId(), sessionId,
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(5)), ZoneOffset.UTC), sessionExpiresAt);
        when(refreshTokenRepositoryService.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(oldToken));
        when(refreshTokenRepositoryService.findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(oldToken));
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenGenerator.generate()).thenReturn(replacementToken);
        when(refreshTokenRepositoryService.markRotated(
                org.mockito.ArgumentMatchers.eq(oldTokenId),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(OffsetDateTime.class))).thenReturn(1);
        RefreshTokenService service = service(properties);

        RefreshTokenService.RotationResult result = service.rotate(submittedToken);

        ArgumentCaptor<RefreshTokenEntity> createdToken = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepositoryService).create(createdToken.capture());
        RefreshTokenEntity replacement = createdToken.getValue();
        assertThat(replacement.getId()).isNotEqualTo(oldTokenId);
        assertThat(replacement.getUserId()).isEqualTo(user.getId());
        assertThat(replacement.getSessionId()).isEqualTo(sessionId);
        assertThat(replacement.getSessionExpiresAt()).isEqualTo(sessionExpiresAt);
        assertThat(replacement.getExpiresAt()).isEqualTo(sessionExpiresAt);
        assertThat(result.user()).isSameAs(user);
        assertThat(result.refreshToken().value()).isEqualTo(replacementToken);
        assertThat(result.refreshToken().expiresAt()).isEqualTo(sessionExpiresAt.toInstant());
        InOrder lockingOrder = inOrder(refreshTokenRepositoryService);
        lockingOrder.verify(refreshTokenRepositoryService).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
        lockingOrder.verify(refreshTokenRepositoryService).lockSession(sessionId);
        lockingOrder.verify(refreshTokenRepositoryService)
                .findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString());
        verify(refreshTokenRepositoryService).markRotated(
                oldTokenId, replacement.getId(), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsMalformedCredentialBeforeQueryingPersistence() {
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate("not-valid"));

        verifyNoInteractions(refreshTokenRepositoryService, userService, refreshTokenGenerator);
    }

    @Test
    void rejectsUnknownWellFormedCredentialWithoutTakingASessionLock() {
        when(refreshTokenRepositoryService.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate("H".repeat(43)));

        verify(refreshTokenRepositoryService).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
        verifyNoMoreInteractions(refreshTokenRepositoryService);
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void revokesAnExpiredCredentialAndReturnsTheUnifiedUnauthorizedError() {
        String submittedToken = "C".repeat(43);
        RefreshTokenEntity expired = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        stubLockedLookup(expired);
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate(submittedToken));

        verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                expired.getSessionId(), RefreshTokenRevokeReason.EXPIRED,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void revokesAWholeSessionAtItsAbsoluteDeadlineEvenWhenTheCredentialHasTimeLeft() {
        String submittedToken = "D".repeat(43);
        RefreshTokenEntity expiredSession = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        stubLockedLookup(expiredSession);
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate(submittedToken));

        verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                expiredSession.getSessionId(), RefreshTokenRevokeReason.EXPIRED,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void revokesTheSessionWhenItsUserIsDisabled() {
        String submittedToken = "E".repeat(43);
        UserEntity user = activeUser();
        user.setStatus(UserStatus.DISABLED);
        RefreshTokenEntity current = activeToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        stubLockedLookup(current);
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate(submittedToken));

        verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                current.getSessionId(), RefreshTokenRevokeReason.USER_UNAVAILABLE,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(refreshTokenGenerator);
    }

    @Test
    void rejectsAnAlreadyRevokedCredentialWithoutChangingItsOriginalReason() {
        String submittedToken = "F".repeat(43);
        RefreshTokenEntity revoked = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        revoked.setRevokedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        revoked.setRevokeReason(RefreshTokenRevokeReason.LOGOUT);
        stubLockedLookup(revoked);
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate(submittedToken));

        verify(refreshTokenRepositoryService).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
        verify(refreshTokenRepositoryService).lockSession(revoked.getSessionId());
        verify(refreshTokenRepositoryService).findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString());
        verifyNoMoreInteractions(refreshTokenRepositoryService);
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void treatsReuseOfARotatedCredentialAsCompromiseOfOnlyThatSession() {
        String submittedToken = "G".repeat(43);
        RefreshTokenEntity rotated = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        rotated.setRevokedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        rotated.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        rotated.setReplacedByTokenId(UUID.randomUUID());
        stubLockedLookup(rotated);
        RefreshTokenService service = service(properties());

        assertUnauthorized(() -> service.rotate(submittedToken));

        verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                rotated.getSessionId(), RefreshTokenRevokeReason.REUSE_DETECTED,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void logoutLocksTheLocatedSessionThenRechecksAndRevokesOnlyActiveCredentials() {
        String submittedToken = "I".repeat(43);
        UUID sessionId = UUID.randomUUID();
        RefreshTokenEntity current = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), sessionId,
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        stubLockedLookup(current);
        RefreshTokenService service = service(properties());

        service.revokeSession(submittedToken);

        InOrder lockingOrder = inOrder(refreshTokenRepositoryService);
        lockingOrder.verify(refreshTokenRepositoryService).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
        lockingOrder.verify(refreshTokenRepositoryService).lockSession(sessionId);
        lockingOrder.verify(refreshTokenRepositoryService)
                .findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString());
        lockingOrder.verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                sessionId, RefreshTokenRevokeReason.LOGOUT, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verifyNoInteractions(userService, refreshTokenGenerator);
    }

    @Test
    void logoutIsIdempotentForMalformedUnknownAndAlreadyRevokedCredentials() {
        RefreshTokenService service = service(properties());
        service.revokeSession("malformed");
        verifyNoInteractions(refreshTokenRepositoryService, userService, refreshTokenGenerator);

        when(refreshTokenRepositoryService.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        service.revokeSession("J".repeat(43));
        verify(refreshTokenRepositoryService).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
        verifyNoMoreInteractions(refreshTokenRepositoryService);

        UUID sessionId = UUID.randomUUID();
        RefreshTokenEntity revoked = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), sessionId,
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        revoked.setRevokedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        revoked.setRevokeReason(RefreshTokenRevokeReason.LOGOUT);
        stubLockedLookup(revoked);
        service.revokeSession("K".repeat(43));
        verify(refreshTokenRepositoryService).lockSession(sessionId);
        verify(refreshTokenRepositoryService).findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString());
        verify(refreshTokenRepositoryService, org.mockito.Mockito.never()).revokeActiveTokensBySessionId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void logoutOfARotatedCredentialStillRevokesItsReplacementSessionAfterTheLock() {
        String submittedToken = "L".repeat(43);
        RefreshTokenEntity rotated = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        rotated.setRevokedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        rotated.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        stubLockedLookup(rotated);
        RefreshTokenService service = service(properties());

        service.revokeSession(submittedToken);

        verify(refreshTokenRepositoryService).revokeActiveTokensBySessionId(
                rotated.getSessionId(), RefreshTokenRevokeReason.LOGOUT,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void logoutLeavesAlreadyExpiredCredentialAndSessionUnchanged() {
        RefreshTokenEntity expiredCredential = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        stubLockedLookup(expiredCredential);
        RefreshTokenService service = service(properties());

        service.revokeSession("M".repeat(43));

        verify(refreshTokenRepositoryService, org.mockito.Mockito.never()).revokeActiveTokensBySessionId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        RefreshTokenEntity expiredSession = activeToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        stubLockedLookup(expiredSession);
        service.revokeSession("N".repeat(43));

        verify(refreshTokenRepositoryService, org.mockito.Mockito.never()).revokeActiveTokensBySessionId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private RefreshTokenService service(RefreshTokenProperties properties) {
        return new RefreshTokenService(
                refreshTokenRepositoryService,
                refreshTokenGenerator,
                new RefreshTokenHashService(),
                properties,
                userService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubLockedLookup(RefreshTokenEntity token) {
        when(refreshTokenRepositoryService.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(token));
        when(refreshTokenRepositoryService.findByTokenHashForUpdate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(token));
    }

    private RefreshTokenProperties properties() {
        return new RefreshTokenProperties(
                Duration.ofDays(14), Duration.ofDays(30),
                "ai_collab_refresh_token", "/api/v1/auth", "Strict", false);
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("owner");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private RefreshTokenEntity activeToken(
            UUID id,
            UUID userId,
            UUID sessionId,
            OffsetDateTime expiresAt,
            OffsetDateTime sessionExpiresAt) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setId(id);
        token.setUserId(userId);
        token.setSessionId(sessionId);
        token.setExpiresAt(expiresAt);
        token.setSessionExpiresAt(sessionExpiresAt);
        token.setCreatedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        return token;
    }

    private void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo("当前登录状态无效，请重新登录");
                });
    }
}
