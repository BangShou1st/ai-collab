package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.config.PublicRegistrationProperties;
import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.dto.RegisterRequest;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class PublicRegistrationService {
    private final PublicRegistrationProperties properties;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final EmailVerificationService emailVerifications;
    /**
     * 已弃用：公开注册开关的唯一事实源是 {@code auth.public-registration.enabled}（env）。
     *
     * <p>保留字段只为兼容既有装配 / 测试注入，注册判定与
     * {@code GET /api/v1/auth/registration-policy} 都不再读取它；DB 中的同名配置不再生效。
     * 表与迁移保留，不做破坏性清理。</p>
     */
    @Deprecated(forRemoval = false)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.system.application.service.SystemConfigService systemConfigService;

    public PublicRegistrationService(
            PublicRegistrationProperties properties,
            UserService users,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
        AuditService audit,
        EmailVerificationService emailVerifications) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.emailVerifications = emailVerifications;
    }

    /**
     * 公开注册是否开启：只以 {@code auth.public-registration.enabled}（env）为单一事实源。
     *
     * <p>策略端点与 {@link #register} 共用本方法，避免出现"策略说开启、实际注册返回
     * REGISTRATION_DISABLED"的双源不一致（D3）。</p>
     */
    public boolean isEnabled() {
        return properties.enabled();
    }

    @Transactional
    public AuthenticationResult register(RegisterRequest request) {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.REGISTRATION_DISABLED);
        }
        String email = normalizeEmail(request.email());
        if (users.findByUsername(request.username()).isPresent()) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (email != null && users.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        try {
            users.create(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        audit.write(null, user.getId(), "USER_REGISTERED", "USER", user.getId());
        if (email != null) {
            emailVerifications.requestFor(user.getId());
        }
        AccessTokenService.IssuedToken accessToken = accessTokens.createAccessToken(user);
        IssuedRefreshToken refreshToken = refreshTokens.createSession(user);
        return new AuthenticationResult(
                new LoginResponse(
                        accessToken.value(), "Bearer", accessToken.expiresInSeconds(), CurrentUserResponse.from(user)),
                refreshToken);
    }

    private static String normalizeEmail(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
