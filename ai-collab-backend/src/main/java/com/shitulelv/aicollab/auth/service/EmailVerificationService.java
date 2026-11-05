package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.auth.lifecycle.entity.AccountActionTokenEntity;
import com.shitulelv.aicollab.auth.lifecycle.service.AccountActionTokenService;
import com.shitulelv.aicollab.auth.dto.AccountEmailDispatchResult;
import com.shitulelv.aicollab.auth.ratelimit.AccountEmailCooldown;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.web.PublicAppUrlService;
import com.shitulelv.aicollab.mail.AccountMailDispatcher;
import com.shitulelv.aicollab.mail.MailMessage;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * 邮箱验证闭环：注册 / 修改邮箱后发送验证邮件，验证成功才标记 email_verified。
 * 令牌只存哈希、24 小时有效、一次性消费。
 */
@Service
public class EmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final UserService users;
    private final AccountActionTokenService tokens;
    private final PublicAppUrlService publicAppUrls;
    private final AccountMailDispatcher mails;
    private final AuditService audit;

    public EmailVerificationService(
            UserService users,
            AccountActionTokenService tokens,
            PublicAppUrlService publicAppUrls,
            AccountMailDispatcher mails,
            AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.publicAppUrls = publicAppUrls;
        this.mails = mails;
        this.audit = audit;
    }

    /**
     * 为用户发送验证邮件。未配置 APP_PUBLIC_URL 时静默跳过（不阻断注册/改邮箱主流程）。
     */
    @Transactional
    public AccountEmailDispatchResult requestFor(UUID userId) {
        return requestFor(userId, false);
    }

    /** 邮箱地址变化时先作废旧邮箱的验证链接，再尝试取得账号级发送许可。 */
    public AccountEmailDispatchResult requestForEmailChange(UUID userId) {
        return requestFor(userId, true);
    }

    public void invalidatePendingForEmailChange(UUID userId) {
        tokens.invalidateActive(userId, AccountActionTokenService.TYPE_EMAIL_VERIFICATION);
    }

    private AccountEmailDispatchResult requestFor(UUID userId, boolean emailChanged) {
        UserEntity user = users.findById(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请先在账号设置中绑定邮箱");
        }
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        if (emailChanged) {
            tokens.invalidateActive(user.getId(), AccountActionTokenService.TYPE_EMAIL_VERIFICATION);
        }
        String link = publicAppUrls.publicUrl()
                .map(base -> base + "/verify-email?token=")
                .orElse(null);
        if (link == null) {
            log.warn("APP_PUBLIC_URL 未配置，跳过邮箱验证邮件（userId={}）", userId);
            return AccountEmailDispatchResult.unavailable();
        }

        AccountEmailCooldown.Decision decision = mails.reserve(userId);
        if (decision.status() == AccountEmailCooldown.Status.COOLDOWN) {
            return AccountEmailDispatchResult.cooldown(decision.retryAfterSeconds());
        }
        if (!decision.reserved()) {
            return AccountEmailDispatchResult.unavailable();
        }

        try {
            if (!emailChanged) {
                tokens.invalidateActive(user.getId(), AccountActionTokenService.TYPE_EMAIL_VERIFICATION);
            }
            String rawToken = tokens.create(
                    user.getId(), AccountActionTokenService.TYPE_EMAIL_VERIFICATION, email, TOKEN_TTL);
            mails.publish(decision.permit(), new MailMessage(
                    email,
                    "AI Collab 邮箱验证",
                    "您好，\n\n请通过以下链接完成邮箱验证（24 小时内有效）：\n\n"
                            + link + rawToken + "\n\n如果您没有注册或修改邮箱，请忽略本邮件。\n\nAI Collab 团队"));
            audit.write(null, userId, "USER_EMAIL_VERIFICATION_REQUESTED", "USER", userId);
            return mails.enabled()
                    ? AccountEmailDispatchResult.queued(decision.retryAfterSeconds())
                    : AccountEmailDispatchResult.unavailable();
        } catch (RuntimeException exception) {
            mails.release(decision.permit());
            throw exception;
        }
    }

    @Transactional
    public void verify(String rawToken) {
        AccountActionTokenEntity token =
                tokens.consume(rawToken, AccountActionTokenService.TYPE_EMAIL_VERIFICATION);
        users.markEmailVerified(token.getUserId());
        audit.write(null, token.getUserId(), "USER_EMAIL_VERIFIED", "USER", token.getUserId());
    }
}
