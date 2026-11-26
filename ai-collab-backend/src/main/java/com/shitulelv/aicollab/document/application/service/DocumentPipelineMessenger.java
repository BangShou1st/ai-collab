package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;

import java.util.UUID;

/**
 * 文档链路 MQ 消息门面（应用层端口；实现位于 {@code document/infrastructure/mq}）。
 *
 * <p>只在 {@code document.mq.enabled=true}（env {@code MQ_ENABLED}，默认开启）时装配。
 * 运维紧急关闭 MQ 时不存在实现：上传受理返回 503；重试 / 批量重建索引的处理通知不发送，
 * 文档保持 UPLOADED，由 {@link DocumentRecoveryService} 对账。</p>
 */
public interface DocumentPipelineMessenger {

    /**
     * 上传受理的事务消息：先发半消息，{@code localRegistration}（注册事务）在 MQ 事务
     * 监听器中执行，事务提交后消息才对消费者可见；应用崩溃时由 broker 回查
     * （以文档状态行为事实源）决定提交或回滚。
     *
     * @return true  = 本地事务已提交；消息可能已确认投递，也可能因 broker 确认未知
     *                 交由回查以状态行为事实源提交，调用方按已受理处理；
     *         false = 半消息发送失败（如 broker 不可用），本地事务尚未执行，
     *                 调用方返回 503，不得在进程内注册。
     * @throws RuntimeException 注册事务抛出的业务/未预期异常（半消息已回滚，
     *                          调用方按原异常语义处理）
     */
    boolean trySendRegistrationTransaction(DocumentEntity document, Runnable localRegistration);

    /**
     * 已提交事务后的处理通知（重试 / 批量重建索引）：发送普通消息。
     * 发送失败仅记录指标与告警日志，不抛出 —— 文档保持 UPLOADED，恢复 Job 对账。
     */
    void sendProcessingNotification(UUID projectId, UUID documentId);
}
