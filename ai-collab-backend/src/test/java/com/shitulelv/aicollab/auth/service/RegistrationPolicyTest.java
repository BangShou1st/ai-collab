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
     * 构造可用的注册服务，并把 {@code SystemConfigService} mock 注入到运行时动态配置字段。
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
     * 动态配置中心开启公开注册时，策略端点与实际注册均以 DB 配置为准。
     */
    @Test
    void db_enabled_overrides_env_disabled() throws Exception {
        SystemConfigService dbEnabled = mock(SystemConfigService.class);
        when(dbEnabled.isPublicRegistrationEnabled()).thenReturn(true);
        PublicRegistrationService service = service(false, dbEnabled);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.register(request()).loginResponse().accessToken()).isEqualTo("access");
    }

    /**
     * 动态配置中心关闭公开注册时，策略端点与实际注册均同步拒绝。
     */
    @Test
    void db_disabled_overrides_env_enabled() throws Exception {
        SystemConfigService dbDisabled = mock(SystemConfigService.class);
        when(dbDisabled.isPublicRegistrationEnabled()).thenReturn(false);
        PublicRegistrationService service = service(true, dbDisabled);

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_DISABLED));
    }

    @Test
    void env_example_documents_public_registration_flag() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        String content = Files.readString(root.resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(content).contains("AUTH_PUBLIC_REGISTRATION_ENABLED=true");
    }
}
