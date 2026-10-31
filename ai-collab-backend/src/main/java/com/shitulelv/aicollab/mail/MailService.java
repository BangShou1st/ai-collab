package com.shitulelv.aicollab.mail;

public interface MailService {

    boolean enabled();

    /**
     * 同步发送；失败抛出异常，由调用方（提交后的监听器）兜底记录，不允许影响数据库事务。
     */
    void send(MailMessage message);
}
