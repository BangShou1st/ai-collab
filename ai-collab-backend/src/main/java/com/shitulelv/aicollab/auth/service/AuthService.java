package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.dto.AccessTokenResponse;
import com.shitulelv.aicollab.auth.dto.LoginRequest;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.model.RefreshResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 认证模块的应用服务，编排用户查询、密码校验、账号状态检查、登录时间更新和 Token 签发。
 * Controller 只调用该用例，不直接访问数据库，从而让认证规则可独立测试并保持事务边界清晰。
 */
@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            UserService userService,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService,
            Clock clock) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
        // 不存在的账号也执行一次真实 BCrypt 比较，减少通过响应耗时枚举用户名的侧信道。
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 用户不存在和密码错误统一报错，避免响应差异帮助攻击者枚举有效账号。
     * 只有密码确实正确后才暴露账号禁用状态，符合本阶段明确的错误契约。
     */
    @Transactional
    public AuthenticationResult login(LoginRequest request) {
        UserEntity user = userService.findByUsername(request.username()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        OffsetDateTime loginAt = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        userService.updateLastLoginAt(user.getId(), loginAt);
        AccessTokenService.IssuedToken token = accessTokenService.createAccessToken(user);
        IssuedRefreshToken refreshToken = refreshTokenService.createSession(user);
        return new AuthenticationResult(
                new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), CurrentUserResponse.from(user)),
                refreshToken);
    }

    /**
     * 同一外层事务覆盖会话轮换与 Access Token 签发；技术异常回滚整次轮换，
     * 而 Refresh Token 状态错误抛出的业务异常仍提交重用检测等安全撤销。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RefreshResult refresh(String submittedRefreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(submittedRefreshToken);
        AccessTokenService.IssuedToken accessToken = accessTokenService.createAccessToken(rotation.user());
        return new RefreshResult(
                new AccessTokenResponse(accessToken.value(), "Bearer", accessToken.expiresInSeconds()),
                rotation.refreshToken());
    }

    /**
     * 当前设备登出只编排 Refresh Token 会话规则；HTTP Cookie 的读取和清除由 Controller 负责。
     */
    public void logout(String submittedRefreshToken) {
        refreshTokenService.revokeSession(submittedRefreshToken);
    }

    /**
     * JWT 只证明 Token 签发时的身份；账号随后可能被禁用或删除，因此仍需读取 app_user。
     * 两种失效情况使用同一 401 响应，既强制客户端重新登录，也不泄露账号当前状态。
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        UserEntity user = userService.findById(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
        return CurrentUserResponse.from(user);
    }
}
