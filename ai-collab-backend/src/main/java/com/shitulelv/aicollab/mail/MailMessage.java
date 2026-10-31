package com.shitulelv.aicollab.mail;

/**
 * 待发送的账号邮件。正文可能包含一次性链接，禁止写入任何日志。
 */
public record MailMessage(String to, String subject, String textBody) {
}
