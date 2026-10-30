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
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 忘记密码 / 密码重置闭环。
 * 请求响应与未知邮箱完全一致，防账号枚举；令牌只存哈希、30 分钟有效、一次性消费；
 * 重置成功后撤销全部 Refresh Token 并递增 tokenVersion 强制全端重新登录。
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final UserService users;
    private final AccountActionTokenService tokens;
    private final AuthRateLimiter rateLimiter;
    private final PublicAppUrlService publicAppUrls;
    private final AccountMailDispatcher mails;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    public PasswordResetService(
            UserService users,
            AccountActionTokenService tokens,
            AuthRateLimiter rateLimiter,
            PublicAppUrlService publicAppUrls,
            AccountMailDispatcher mails,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokens,
            AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.publicAppUrls = publicAppUrls;
        this.mails = mails;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    @Transactional
    public void requestReset(HttpServletRequest request, String email) {
        rateLimiter.checkPasswordReset(request, email);
        String normalized = normalizeEmail(email);
        Optional<UserEntity> candidate = users.findByEmail(normalized)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (candidate.isEmpty()) {
            // 与存在账号的路径耗时相近即可，不做任何可观察区分。
            return;
        }
        UserEntity user = candidate.get();
        tokens.invalidateActive(user.getId(), AccountActionTokenService.TYPE_PASSWORD_RESET);
        String rawToken = tokens.create(
                user.getId(), AccountActionTokenService.TYPE_PASSWORD_RESET, normalized, TOKEN_TTL);
        String link = publicAppUrls.publicUrl()
                .map(base -> base + "/reset-password?token=" + rawToken)
                .orElse(null);
        if (link == null) {
            log.warn("APP_PUBLIC_URL 未配置，已生成密码重置令牌但跳过发送邮件（userId={}）", user.getId());
            return;
        }
        mails.publish(new MailMessage(
                normalized,
                "AI Collab 密码重置",
                "您好，\n\n我们收到了您的密码重置请求。请在 30 分钟内通过以下链接重置密码：\n\n"
                        + link + "\n\n如果您没有发起该请求，请忽略本邮件，您的密码不会发生变化。\n\nAI Collab 团队"));
        audit.write(null, user.getId(), "USER_PASSWORD_RESET_REQUESTED", "USER", user.getId());
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        AccountActionTokenEntity token =
                tokens.consume(rawToken, AccountActionTokenService.TYPE_PASSWORD_RESET);
        UserEntity user = users.findById(token.getUserId())
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID));
        users.updatePassword(user.getId(), passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId());
        audit.write(null, user.getId(), "USER_PASSWORD_RESET_COMPLETED", "USER", user.getId());
    }

    private static String normalizeEmail(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
