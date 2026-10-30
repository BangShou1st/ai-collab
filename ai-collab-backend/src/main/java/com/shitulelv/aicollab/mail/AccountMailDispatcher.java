package com.shitulelv.aicollab.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号邮件与数据库事务解耦：事务内只登记发送意图，提交成功后才真正投递；
 * SMTP 故障只记录错误日志，不允许回滚或中断业务事务。
 */
@Component
public class AccountMailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AccountMailDispatcher.class);

    private final ApplicationEventPublisher events;
    private final MailService mailService;

    public AccountMailDispatcher(ApplicationEventPublisher events, MailService mailService) {
        this.events = events;
        this.mailService = mailService;
    }

    public void publish(MailMessage message) {
        events.publishEvent(message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliverAfterCommit(MailMessage message) {
        try {
            mailService.send(message);
        } catch (RuntimeException exception) {
            log.error("账号邮件发送失败（业务事务已提交，不影响用户操作）: to={}, subject={}, error={}",
                    message.to(), message.subject(), exception.getMessage());
        }
    }
}
