package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.lifecycle.entity.AccountActionTokenEntity;
import com.shitulelv.aicollab.auth.lifecycle.service.AccountActionTokenService;
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

class EmailVerificationServiceTest {

    private UserService users;
    private AccountActionTokenService tokens;
    private PublicAppUrlService publicAppUrls;
    private AccountMailDispatcher mails;
    private EmailVerificationService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        tokens = mock(AccountActionTokenService.class);
        publicAppUrls = mock(PublicAppUrlService.class);
        mails = mock(AccountMailDispatcher.class);
        service = new EmailVerificationService(users, tokens, publicAppUrls, mails, mock(AuditService.class));

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("owner@example.invalid");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
    }

    @Test
    void request_for_verified_email_rejected() {
        user.setEmailVerified(true);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.requestFor(user.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED));
    }

    @Test
    void request_without_bound_email_rejected() {
        user.setEmail(null);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.requestFor(user.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void request_sends_mail_with_trusted_link() {
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(publicAppUrls.publicUrl()).thenReturn(Optional.of("https://aicollab.example.edu"));
        when(tokens.create(eq(user.getId()), anyString(), anyString(), any())).thenReturn("raw-token");

        service.requestFor(user.getId());

        verify(tokens).invalidateActive(user.getId(), AccountActionTokenService.TYPE_EMAIL_VERIFICATION);
        ArgumentCaptor<MailMessage> mail = ArgumentCaptor.forClass(MailMessage.class);
        verify(mails).publish(mail.capture());
        assertThat(mail.getValue().textBody())
                .contains("https://aicollab.example.edu/verify-email?token=raw-token");
    }

    @Test
    void verify_marks_email_verified_and_audits() {
        AccountActionTokenEntity token = new AccountActionTokenEntity();
        token.setUserId(user.getId());
        when(tokens.consume("raw-token", AccountActionTokenService.TYPE_EMAIL_VERIFICATION)).thenReturn(token);

        service.verify("raw-token");

        verify(users).markEmailVerified(user.getId());
    }

    @Test
    void verify_with_invalid_token_propagates() {
        doThrow(new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID))
                .when(tokens).consume("bad-token", AccountActionTokenService.TYPE_EMAIL_VERIFICATION);

        assertThatThrownBy(() -> service.verify("bad-token"))
                .isInstanceOf(BusinessException.class);
        verify(users, never()).markEmailVerified(any());
    }
}
