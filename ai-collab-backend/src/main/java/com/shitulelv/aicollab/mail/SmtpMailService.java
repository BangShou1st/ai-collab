package com.shitulelv.aicollab.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SmtpMailService implements MailService {

    private final JavaMailSenderImpl mailSender;
    private final String from;

    public SmtpMailService(
            @Value("${app.mail.host:}") String host,
            @Value("${app.mail.port:587}") int port,
            @Value("${app.mail.username:}") String username,
            @Value("${app.mail.password:}") String password,
            @Value("${app.mail.starttls:true}") boolean starttls,
            @Value("${app.mail.from:}") String from) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("MAIL_ENABLED=true 时必须配置 MAIL_HOST");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("MAIL_ENABLED=true 时必须配置 MAIL_FROM");
        }
        this.from = from;
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        boolean authEnabled = username != null && !username.isBlank();
        if (authEnabled) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", Boolean.toString(authEnabled));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(starttls));
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        this.mailSender = sender;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void send(MailMessage message) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(message.to());
            mail.setSubject(message.subject());
            mail.setText(message.textBody());
            mailSender.send(mail);
        } catch (MailException exception) {
            // 异常信息可能携带 SMTP 会话细节，但不包含正文令牌；由 AFTER_COMMIT 监听器兜底记录。
            throw new IllegalStateException("邮件发送失败: " + exception.getMessage(), exception);
        }
    }
}
