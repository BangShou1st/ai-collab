package com.shitulelv.aicollab.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 未配置 SMTP 时的降级实现：只记录收件人与主题，正文（含一次性链接）绝不写日志。
 */
@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingMailService implements MailService {
    private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void send(MailMessage message) {
        log.info("邮件服务未启用（MAIL_ENABLED=false），已跳过发送: to={}, subject={}",
                message.to(), message.subject());
    }
}
