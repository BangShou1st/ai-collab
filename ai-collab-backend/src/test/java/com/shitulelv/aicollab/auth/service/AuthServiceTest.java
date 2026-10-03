package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.dto.LoginRequest;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T02:00:00Z");

    private UserService userService;
    private PasswordEncoder passwordEncoder;
    private AccessTokenService accessTokenService;
    private RefreshTokenService refreshTokenService;
    private AuthService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        accessTokenService = mock(AccessTokenService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        // AuthService 构造器会为防用户枚举生成一次 dummy hash，必须先打桩再构造
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        service = new AuthService(
                userService, passwordEncoder, accessTokenService, refreshTokenService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("owner");
        user.setPasswordHash("bcrypt-hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemAdmin(false);
    }

    private LoginRequest loginRequest() {
        return new LoginRequest("owner", "password");
    }

    @Test
    void login_success_updates_last_login_and_issues_both_tokens() {
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "bcrypt-hash")).thenReturn(true);
        AccessTokenService.IssuedToken issuedToken =
                new AccessTokenService.IssuedToken("access-jwt", 1800);
        when(accessTokenService.createAccessToken(user)).thenReturn(issuedToken);
        IssuedRefreshToken issuedRefresh =
                new IssuedRefreshToken("refresh-token", NOW.plusSeconds(3600));
        when(refreshTokenService.createSession(user)).thenReturn(issuedRefresh);

        AuthenticationResult result = service.login(loginRequest());

        assertThat(result.loginResponse().accessToken()).isEqualTo("access-jwt");
        assertThat(result.loginResponse().tokenType()).isEqualTo("Bearer");
        assertThat(result.loginResponse().user().username()).isEqualTo("owner");
        assertThat(result.refreshToken()).isSameAs(issuedRefresh);
        verify(userService).updateLastLoginAt(eq(user.getId()), any());
    }

    @Test
    void login_unknown_username_rejects_with_invalid_credentials() {
        when(userService.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost", "password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        // 不存在的账号也要执行一次真实比较，避免耗时侧信道枚举用户名
        verify(passwordEncoder).matches(eq("password"), anyString());
        verify(userService, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void login_wrong_password_rejects_with_invalid_credentials() {
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(refreshTokenService, never()).createSession(any());
    }

    @Test
    void login_disabled_account_rejected_after_correct_password() {
        user.setStatus(UserStatus.DISABLED);
        when(userService.findByUsername("owner")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "bcrypt-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login(loginRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.USER_DISABLED));
        verify(userService, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void refresh_issues_new_access_token_from_rotation() {
        UserEntity rotationUser = new UserEntity();
        rotationUser.setId(UUID.randomUUID());
        rotationUser.setUsername("owner");
        rotationUser.setStatus(UserStatus.ACTIVE);
        when(refreshTokenService.rotate("submitted-token"))
                .thenReturn(new RefreshTokenService.RotationResult(
                        rotationUser, new IssuedRefreshToken("new-refresh", NOW.plusSeconds(3600))));
        when(accessTokenService.createAccessToken(rotationUser))
                .thenReturn(new AccessTokenService.IssuedToken("new-access", 1800));

        var result = service.refresh("submitted-token");

        assertThat(result.accessTokenResponse().accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken().value()).isEqualTo("new-refresh");
    }

    @Test
    void refresh_failure_propagates_business_error() {
        when(refreshTokenService.rotate("bad-token"))
                .thenThrow(new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));

        assertThatThrownBy(() -> service.refresh("bad-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
        verify(accessTokenService, never()).createAccessToken(any());
    }

    @Test
    void logout_revokes_current_session() {
        service.logout("token-value");

        verify(refreshTokenService).revokeSession("token-value");
    }

    @Test
    void current_user_rejects_disabled_account() {
        user.setStatus(UserStatus.DISABLED);
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.getCurrentUser(user.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
    }

    @Test
    void current_user_returns_active_user() {
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.getCurrentUser(user.getId()).username()).isEqualTo("owner");
    }

    @Test
    void current_user_rejects_unknown_user() {
        when(userService.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentUser(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
    }
}
