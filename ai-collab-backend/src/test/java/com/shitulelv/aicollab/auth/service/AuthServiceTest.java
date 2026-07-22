package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.dto.LoginRequest;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.model.RefreshResult;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T15:00:00Z");

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        authService = new AuthService(userService, passwordEncoder, accessTokenService, refreshTokenService, clock);
    }

    @Test
    void returnsTokenAndSafeUserViewForValidCredentials() {
        UserEntity user = activeUser();
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("12345678", user.getPasswordHash())).thenReturn(true);
        when(accessTokenService.createAccessToken(user))
                .thenReturn(new AccessTokenService.IssuedToken("signed.jwt", 1800));
        when(refreshTokenService.createSession(user))
                .thenReturn(new IssuedRefreshToken("refresh-token", NOW.plusSeconds(60)));

        AuthenticationResult result = authService.login(new LoginRequest("owner", "12345678"));
        LoginResponse response = result.loginResponse();

        assertThat(response.accessToken()).isEqualTo("signed.jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(1800);
        assertThat(response.user().id()).isEqualTo(user.getId());
        assertThat(response.user().username()).isEqualTo("owner");
        assertThat(result.refreshToken().value()).isEqualTo("refresh-token");
        verify(userService).updateLastLoginAt(user.getId(), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(refreshTokenService).createSession(user);
    }

    @Test
    void returnsUnifiedCredentialsErrorWhenPasswordIsWrong() {
        UserEntity user = activeUser();
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("owner", "wrong")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
                    assertThat(exception.getMessage()).isEqualTo("用户名或密码错误");
                });
        verifyNoInteractions(accessTokenService);
    }

    @Test
    void returnsSameCredentialsErrorWhenUserDoesNotExist() {
        when(userService.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing", "12345678")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(passwordEncoder).matches("12345678", "dummy-hash");
        verifyNoInteractions(accessTokenService);
    }

    @Test
    void rejectsDisabledUserAfterCorrectPassword() {
        UserEntity user = activeUser();
        user.setStatus(UserStatus.DISABLED);
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("12345678", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("owner", "12345678")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_DISABLED);
                    assertThat(exception.getMessage()).isEqualTo("账号已被禁用");
                });
        verifyNoInteractions(accessTokenService);
    }

    @Test
    void returnsCurrentUserWhenTokenSubjectStillMapsToActiveAccount() {
        UserEntity user = activeUser();
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(authService.getCurrentUser(user.getId()).username()).isEqualTo("owner");
    }

    @Test
    void hidesWhetherTokenSubjectUserIsMissingOrDisabled() {
        UUID userId = UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851");
        when(userService.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getCurrentUser(userId))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo("当前登录状态无效，请重新登录");
                });

        UserEntity disabled = activeUser();
        disabled.setStatus(UserStatus.DISABLED);
        when(userService.findById(userId)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> authService.getCurrentUser(userId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
    }

    @Test
    void refreshesTheSessionBeforeIssuingANewAccessToken() {
        UserEntity user = activeUser();
        IssuedRefreshToken replacement = new IssuedRefreshToken(
                "replacement-placeholder", NOW.plusSeconds(3600));
        when(refreshTokenService.rotate("submitted-placeholder"))
                .thenReturn(new RefreshTokenService.RotationResult(user, replacement));
        when(accessTokenService.createAccessToken(user))
                .thenReturn(new AccessTokenService.IssuedToken("new.access.jwt", 1800));

        RefreshResult result = authService.refresh("submitted-placeholder");

        assertThat(result.accessTokenResponse().accessToken()).isEqualTo("new.access.jwt");
        assertThat(result.accessTokenResponse().tokenType()).isEqualTo("Bearer");
        assertThat(result.accessTokenResponse().expiresInSeconds()).isEqualTo(1800);
        assertThat(result.refreshToken()).isSameAs(replacement);
        verify(refreshTokenService).rotate("submitted-placeholder");
        verify(accessTokenService).createAccessToken(user);
    }

    @Test
    void delegatesLogoutToTheRefreshTokenSessionService() {
        authService.logout("submitted-placeholder");

        verify(refreshTokenService).revokeSession("submitted-placeholder");
        verifyNoInteractions(accessTokenService, userService);
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.fromString("99cb9d98-9128-4930-a56c-86360a641851"));
        user.setUsername("owner");
        user.setPasswordHash("$2a$12$stored-hash");
        user.setDisplayName("Local Owner");
        user.setEmail(null);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
