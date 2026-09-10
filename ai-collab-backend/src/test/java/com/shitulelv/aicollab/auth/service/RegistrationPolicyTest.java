package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.config.PublicRegistrationProperties;
import com.shitulelv.aicollab.auth.dto.RegisterRequest;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationPolicyTest {

    private PublicRegistrationService service(boolean enabled) {
        return new PublicRegistrationService(new PublicRegistrationProperties(enabled),
                mock(UserService.class), mock(PasswordEncoder.class),
                mock(AccessTokenService.class), mock(RefreshTokenService.class),
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class));
    }

    private RegisterRequest request() {
        return new RegisterRequest("newcomer", "password123", "Newcomer", null);
    }

    @Test
    void disabled_registration_rejects() {
        assertThat(service(false).isEnabled()).isFalse();
        assertThatThrownBy(() -> service(false).register(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_DISABLED));
    }

    @Test
    void enabled_registration_accepts_new_username() {
        UserService users = mock(UserService.class);
        when(users.findByUsername("newcomer")).thenReturn(Optional.empty());
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode(any())).thenReturn("hash");
        AccessTokenService accessTokens = mock(AccessTokenService.class);
        when(accessTokens.createAccessToken(any()))
                .thenReturn(new AccessTokenService.IssuedToken("access", 1800L));
        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        PublicRegistrationService service = new PublicRegistrationService(
                new PublicRegistrationProperties(true), users, passwords, accessTokens,
                refreshTokens,
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class));

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.register(request()).loginResponse().accessToken()).isEqualTo("access");
    }

    @Test
    void env_example_documents_public_registration_flag() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        String content = Files.readString(root.resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(content).contains("AUTH_PUBLIC_REGISTRATION_ENABLED=true");
    }
}
