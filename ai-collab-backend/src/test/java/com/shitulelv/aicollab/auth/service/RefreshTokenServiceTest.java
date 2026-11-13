package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.refresh.entity.RefreshTokenEntity;
import com.shitulelv.aicollab.auth.refresh.model.RefreshTokenRevokeReason;
import com.shitulelv.aicollab.auth.refresh.service.RefreshTokenRepositoryService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.RefreshTokenProperties;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final String TOKEN = "A".repeat(43);
    private static final String NEXT_TOKEN = "B".repeat(43);

    private RefreshTokenRepositoryService repository;
    private RefreshTokenGenerator generator;
    private RefreshTokenHashService hashes;
    private RefreshTokenProperties properties;
    private UserService users;
    private RefreshTokenService service;
    private UserEntity user;
    private RefreshTokenEntity current;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepositoryService.class);
        generator = mock(RefreshTokenGenerator.class);
        hashes = mock(RefreshTokenHashService.class);
        properties = mock(RefreshTokenProperties.class);
        users = mock(UserService.class);
        service = new RefreshTokenService(
                repository, generator, hashes, properties, users, Clock.fixed(NOW, ZoneOffset.UTC));
        when(properties.tokenLifetime()).thenReturn(java.time.Duration.ofDays(14));
        when(properties.sessionLifetime()).thenReturn(java.time.Duration.ofDays(30));
        when(hashes.hash(TOKEN)).thenReturn("hash");
        when(generator.tokenVersion(TOKEN)).thenReturn(OptionalInt.of(3));

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(3);

        current = new RefreshTokenEntity();
        current.setId(UUID.randomUUID());
        current.setUserId(user.getId());
        current.setTokenHash("hash");
        current.setSessionId(UUID.randomUUID());
        current.setExpiresAt(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        current.setSessionExpiresAt(OffsetDateTime.ofInstant(NOW.plusSeconds(7200), ZoneOffset.UTC));
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(current));
        when(repository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(current));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(generator.generate(3)).thenReturn(NEXT_TOKEN);
        when(repository.markRotated(any(), any(), any())).thenReturn(1);
    }

    @Test
    void create_session_encodes_current_token_version() {
        when(generator.generate(3)).thenReturn(TOKEN);
        when(hashes.hash(TOKEN)).thenReturn("new-hash");

        service.createSession(user);

        verify(generator).generate(3);
        ArgumentCaptor<RefreshTokenEntity> captor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository).create(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo("new-hash");
        assertThat(captor.getValue().getSessionId()).isNotNull();
    }

    @Test
    void rotate_rejects_token_whose_version_is_stale_and_revokes_its_session() {
        when(generator.tokenVersion(TOKEN)).thenReturn(OptionalInt.of(2));

        assertThatThrownBy(() -> service.rotate(TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
        verify(repository).revokeActiveTokensBySessionId(
                eq(current.getSessionId()), eq(RefreshTokenRevokeReason.USER_UNAVAILABLE), any());
        verify(repository, never()).create(any());
    }

    @Test
    void rotate_rejects_disabled_user_and_revokes_session() {
        user.setStatus(UserStatus.DISABLED);

        assertThatThrownBy(() -> service.rotate(TOKEN))
                .isInstanceOf(BusinessException.class);
        verify(repository).revokeActiveTokensBySessionId(
                eq(current.getSessionId()), eq(RefreshTokenRevokeReason.USER_UNAVAILABLE), any());
    }

    @Test
    void rotate_issues_replacement_at_current_version() {
        RefreshTokenService.RotationResult result = service.rotate(TOKEN);

        assertThat(result.refreshToken().value()).isEqualTo(NEXT_TOKEN);
        assertThat(result.user().getId()).isEqualTo(user.getId());
        verify(generator).generate(3);
        verify(repository).markRotated(eq(current.getId()), any(), any());
    }

    @Test
    void legacy_versionless_token_rotates_only_for_version_zero_user() {
        user.setTokenVersion(0);
        when(generator.tokenVersion(TOKEN)).thenReturn(OptionalInt.of(0));
        when(generator.generate(0)).thenReturn(NEXT_TOKEN);

        RefreshTokenService.RotationResult result = service.rotate(TOKEN);

        assertThat(result.refreshToken().value()).isEqualTo(NEXT_TOKEN);
        verify(generator).generate(0);
    }

    @Test
    void malformed_version_is_rejected_before_database_lookup() {
        when(generator.tokenVersion(TOKEN)).thenReturn(OptionalInt.empty());

        assertThatThrownBy(() -> service.rotate(TOKEN))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).findByTokenHash(anyString());
    }

    @Test
    void revoke_all_uses_requested_reason() {
        service.revokeAllForUser(user.getId(), RefreshTokenRevokeReason.USER_UNAVAILABLE);

        verify(repository).revokeActiveTokensByUserId(
                eq(user.getId()), eq(RefreshTokenRevokeReason.USER_UNAVAILABLE), any());
    }
}
