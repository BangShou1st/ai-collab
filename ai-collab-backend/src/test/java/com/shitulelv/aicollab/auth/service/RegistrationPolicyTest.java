package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.config.PublicRegistrationProperties;
import com.shitulelv.aicollab.auth.dto.RegisterRequest;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
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
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class),
                mock(EmailVerificationService.class));
    }

    /**
     * 构造可用的注册服务，并把 {@code SystemConfigService} mock 注入到已弃用的兼容字段。
     *
     * <p>生产构造器保持不变；D3 的测试需要证明该字段即使返回 true 也不影响注册判定。</p>
     */
    private PublicRegistrationService service(boolean enabled, SystemConfigService systemConfig)
            throws ReflectiveOperationException {
        UserService users = mock(UserService.class);
        when(users.findByUsername("newcomer")).thenReturn(Optional.empty());
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode(any())).thenReturn("hash");
        AccessTokenService accessTokens = mock(AccessTokenService.class);
        when(accessTokens.createAccessToken(any()))
                .thenReturn(new AccessTokenService.IssuedToken("access", 1800L));
        PublicRegistrationService service = new PublicRegistrationService(
                new PublicRegistrationProperties(enabled), users, passwords, accessTokens,
                mock(RefreshTokenService.class),
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class),
                mock(EmailVerificationService.class));
        Field field = PublicRegistrationService.class.getDeclaredField("systemConfigService");
        field.setAccessible(true);
        field.set(service, systemConfig);
        return service;
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
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class),
                mock(EmailVerificationService.class));

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.register(request()).loginResponse().accessToken()).isEqualTo("access");
    }

    /**
     * D3：DB 说是开启，但 env 关闭 —— 策略端点与实际注册都必须以 env 为准。
     */
    @Test
    void db_enabled_cannot_override_env_disabled() throws Exception {
        SystemConfigService dbEnabled = mock(SystemConfigService.class);
        when(dbEnabled.isPublicRegistrationEnabled()).thenReturn(true);
        PublicRegistrationService service = service(false, dbEnabled);

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_DISABLED));
    }

    /**
     * D3：DB 说是关闭，但 env 开启 —— 注册仍然接受，策略端点与注册行为一致。
     */
    @Test
    void db_disabled_cannot_block_env_enabled() throws Exception {
        SystemConfigService dbDisabled = mock(SystemConfigService.class);
        when(dbDisabled.isPublicRegistrationEnabled()).thenReturn(false);
        PublicRegistrationService service = service(true, dbDisabled);

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
