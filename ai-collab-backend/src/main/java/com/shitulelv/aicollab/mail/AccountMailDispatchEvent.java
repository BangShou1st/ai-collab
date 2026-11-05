package com.shitulelv.aicollab.mail;

import com.shitulelv.aicollab.auth.ratelimit.AccountEmailCooldown;

/** 邮件只在业务事务提交后投递；许可用于完成、释放或回滚账号邮件冷却。 */
public record AccountMailDispatchEvent(MailMessage message, AccountEmailCooldown.Permit permit) {
}
