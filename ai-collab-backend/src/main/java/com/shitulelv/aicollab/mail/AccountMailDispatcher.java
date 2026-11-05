package com.shitulelv.aicollab.mail;

import com.shitulelv.aicollab.auth.ratelimit.AccountEmailCooldown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * 账号邮件与数据库事务解耦：事务内只登记发送意图，提交成功后才真正投递；
 * SMTP 故障只记录错误日志，不允许回滚或中断业务事务。
 */
@Component
public class AccountMailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AccountMailDispatcher.class);

    private final ApplicationEventPublisher events;
    private final MailService mailService;
    private final AccountEmailCooldown cooldown;

    public AccountMailDispatcher(
            ApplicationEventPublisher events,
            MailService mailService,
            AccountEmailCooldown cooldown) {
        this.events = events;
        this.mailService = mailService;
        this.cooldown = cooldown;
    }

    public AccountEmailCooldown.Decision reserve(UUID accountId) {
        return cooldown.reserve(accountId);
    }

    public boolean enabled() {
        return mailService.enabled();
    }

    public void publish(AccountEmailCooldown.Permit permit, MailMessage message) {
        events.publishEvent(new AccountMailDispatchEvent(message, permit));
    }

    public void release(AccountEmailCooldown.Permit permit) {
        cooldown.release(permit);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliverAfterCommit(AccountMailDispatchEvent event) {
        try {
            mailService.send(event.message());
            if (mailService.enabled()) {
                cooldown.markSent(event.permit());
            } else {
                cooldown.release(event.permit());
            }
        } catch (RuntimeException exception) {
            log.error("账号邮件发送失败（业务事务已提交，不影响用户操作）: to={}, subject={}, error={}",
                    event.message().to(), event.message().subject(), exception.getMessage());
            cooldown.release(event.permit());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void releaseAfterRollback(AccountMailDispatchEvent event) {
        cooldown.release(event.permit());
    }
}
