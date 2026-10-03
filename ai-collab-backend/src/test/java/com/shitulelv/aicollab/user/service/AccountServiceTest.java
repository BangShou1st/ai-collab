package com.shitulelv.aicollab.user.service;

import com.shitulelv.aicollab.auth.dto.ChangePasswordRequest;
import com.shitulelv.aicollab.auth.service.RefreshTokenService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.dto.UpdateCurrentUserRequest;
import com.shitulelv.aicollab.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private UserService users;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenService refreshTokens;
    private AuditService audit;
    private AccountService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokens = mock(RefreshTokenService.class);
        audit = mock(AuditService.class);
        service = new AccountService(users, passwordEncoder, refreshTokens, audit);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("owner");
        user.setPasswordHash("current-hash");
        user.setStatus(com.shitulelv.aicollab.user.model.UserStatus.ACTIVE);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void change_password_success_encodes_and_revokes_all_sessions() {
        when(passwordEncoder.matches("current", "current-hash")).thenReturn(true);
        when(passwordEncoder.matches("new-password", "current-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(users.updatePassword(user.getId(), "new-hash")).thenReturn(true);

        service.changePassword(user.getId(), new ChangePasswordRequest("current", "new-password"));

        verify(users).updatePassword(user.getId(), "new-hash");
        verify(refreshTokens).revokeAllForUser(user.getId());
        verify(audit).write(isNull(), eq(user.getId()), eq("USER_PASSWORD_CHANGED"), eq("USER"), eq(user.getId()));
    }

    @Test
    void change_password_wrong_current_password_rejected() {
        when(passwordEncoder.matches("wrong", "current-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(user.getId(),
                new ChangePasswordRequest("wrong", "new-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CURRENT_PASSWORD_INVALID));
        verify(users, never()).updatePassword(any(), anyString());
        verify(refreshTokens, never()).revokeAllForUser(any());
    }

    @Test
    void change_password_same_as_current_rejected() {
        when(passwordEncoder.matches("current", "current-hash")).thenReturn(true);
        when(passwordEncoder.matches("same-password", "current-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(user.getId(),
                new ChangePasswordRequest("current", "same-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT));
    }

    @Test
    void update_profile_rejects_email_owned_by_another_user() {
        UserEntity other = new UserEntity();
        other.setId(UUID.randomUUID());
        when(users.findByEmail("taken@example.com")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updateProfile(user.getId(),
                new UpdateCurrentUserRequest("显示名", "taken@example.com")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));
    }

    @Test
    void update_profile_normalizes_email_and_audits() {
        when(users.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(users.updateProfile(eq(user.getId()), eq("显示名"), eq("user@example.com"))).thenReturn(true);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        service.updateProfile(user.getId(), new UpdateCurrentUserRequest(" 显示名 ", "USER@example.com"));

        verify(users).updateProfile(user.getId(), "显示名", "user@example.com");
        verify(audit).write(isNull(), eq(user.getId()), eq("USER_PROFILE_UPDATED"), eq("USER"), eq(user.getId()));
    }

    @Test
    void logout_all_requires_existing_user_and_revokes_sessions() {
        service.logoutAll(user.getId());

        verify(refreshTokens).revokeAllForUser(user.getId());
        verify(audit).write(isNull(), eq(user.getId()), eq("USER_LOGOUT_ALL"), eq("USER"), eq(user.getId()));
    }
}
