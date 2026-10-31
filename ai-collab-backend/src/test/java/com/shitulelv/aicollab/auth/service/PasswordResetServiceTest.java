package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.lifecycle.entity.AccountActionTokenEntity;
import com.shitulelv.aicollab.auth.lifecycle.service.AccountActionTokenService;
import com.shitulelv.aicollab.auth.ratelimit.AuthRateLimiter;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.web.PublicAppUrlService;
import com.shitulelv.aicollab.mail.AccountMailDispatcher;
import com.shitulelv.aicollab.mail.MailMessage;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private UserService users;
    private AccountActionTokenService tokens;
    private PublicAppUrlService publicAppUrls;
    private AccountMailDispatcher mails;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenService refreshTokens;
    private PasswordResetService service;
    private UserEntity user;
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        tokens = mock(AccountActionTokenService.class);
        AuthRateLimiter rateLimiter = mock(AuthRateLimiter.class);
        publicAppUrls = mock(PublicAppUrlService.class);
        mails = mock(AccountMailDispatcher.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokens = mock(RefreshTokenService.class);
        service = new PasswordResetService(users, tokens, rateLimiter, publicAppUrls,
                mails, passwordEncoder, refreshTokens, mock(AuditService.class));

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("owner@example.invalid");
        user.setStatus(UserStatus.ACTIVE);
    }

    @Test
    void request_reset_sends_mail_with_trusted_link_and_rotates_tokens() {
        when(users.findByEmail("owner@example.invalid")).thenReturn(Optional.of(user));
        when(publicAppUrls.publicUrl()).thenReturn(Optional.of("https://aicollab.example.edu"));
        when(tokens.create(eq(user.getId()), anyString(), anyString(), any())).thenReturn("raw-token");

        service.requestReset(request, "Owner@Example.invalid");

        verify(tokens).invalidateActive(user.getId(), AccountActionTokenService.TYPE_PASSWORD_RESET);
        ArgumentCaptor<MailMessage> mail = ArgumentCaptor.forClass(MailMessage.class);
        verify(mails).publish(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("owner@example.invalid");
        assertThat(mail.getValue().textBody())
                .contains("https://aicollab.example.edu/reset-password?token=raw-token");
        assertThat(mail.getValue().textBody()).doesNotContain("\n\n\n");
    }

    @Test
    void request_reset_for_unknown_email_is_indistinguishable_and_silent() {
        when(users.findByEmail("ghost@example.invalid")).thenReturn(Optional.empty());

        service.requestReset(request, "ghost@example.invalid");

        verify(tokens, never()).create(any(), anyString(), anyString(), any());
        verify(mails, never()).publish(any());
    }

    @Test
    void request_reset_without_public_url_skips_mail_but_keeps_token() {
        when(users.findByEmail("owner@example.invalid")).thenReturn(Optional.of(user));
        when(publicAppUrls.publicUrl()).thenReturn(Optional.empty());
        when(tokens.create(eq(user.getId()), anyString(), anyString(), any())).thenReturn("raw-token");

        service.requestReset(request, "owner@example.invalid");

        verify(mails, never()).publish(any());
    }

    @Test
    void reset_consumes_token_updates_password_and_revokes_sessions() {
        AccountActionTokenEntity token = new AccountActionTokenEntity();
        token.setUserId(user.getId());
        when(tokens.consume("raw-token", AccountActionTokenService.TYPE_PASSWORD_RESET)).thenReturn(token);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password-123")).thenReturn("encoded-hash");

        service.reset("raw-token", "new-password-123");

        verify(users).updatePassword(user.getId(), "encoded-hash");
        verify(refreshTokens).revokeAllForUser(user.getId());
    }

    @Test
    void reset_with_invalid_token_propagates_and_changes_nothing() {
        doThrow(new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID))
                .when(tokens).consume("bad-token", AccountActionTokenService.TYPE_PASSWORD_RESET);

        assertThatThrownBy(() -> service.reset("bad-token", "new-password-123"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_TOKEN_INVALID));
        verify(users, never()).updatePassword(any(), anyString());
        verify(refreshTokens, never()).revokeAllForUser(any());
    }
}
