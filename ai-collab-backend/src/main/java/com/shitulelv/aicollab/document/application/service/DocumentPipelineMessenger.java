package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;

import java.util.UUID;

/**
 * 文档链路 MQ 消息门面（应用层端口；实现位于 {@code document/infrastructure/mq}）。
 *
 * <p>M1 迁移语义：MQ 总闸（{@code MQ_ENABLED} / {@code document.mq.enabled}）关闭时
 * {@link #enabled()} 返回 false，文档链路完全回落进程内派发路径；开启时上传受理走
 * RocketMQ 事务消息，重试/批量重建索引等"已提交事务后的处理通知"走普通消息。</p>
 *
 * <p>设计基调是"消息丢失无害"：文档状态行（project_document.status）本身就是事实源，
 * 任何消息发送/投递失败最终都由 {@link DocumentRecoveryService} 的对账兜底，
 * 因此门面的所有失败路径都不允许阻塞主流程（唯一例外是注册业务异常需按原语义透传）。</p>
 */
public interface DocumentPipelineMessenger {

    /** MQ 总闸是否开启。开启时调用方应走 MQ 路径，关闭时走进程内派发。 */
    boolean enabled();

    /**
     * 上传受理的事务消息：先发半消息，{@code localRegistration}（注册事务）在 MQ 事务
     * 监听器中执行，事务提交后消息才对消费者可见；应用崩溃时由 broker 回查
     * （以文档状态行为事实源）决定提交或回滚。
     *
     * @return true  = 半消息发送且本地事务已提交，消息将投递给消费者；
     *         false = 半消息发送失败（如 broker 不可用），注册事务尚未执行，
     *                 调用方应回落进程内路径重新执行注册与派发。
     * @throws RuntimeException 注册事务抛出的业务/未预期异常（半消息已回滚，
     *                          调用方按原异常语义处理，行为与进程内路径一致）
     */
    boolean trySendRegistrationTransaction(DocumentEntity document, Runnable localRegistration);

    /**
     * 已提交事务后的处理通知（重试 / 批量重建索引 / 回落注册等场景）：发送普通消息。
     * 发送失败仅记录指标与告警日志，不抛出 —— 恢复 Job 对账兜底（消息丢失无害设计）。
     */
    void sendProcessingNotification(UUID projectId, UUID documentId);
}
