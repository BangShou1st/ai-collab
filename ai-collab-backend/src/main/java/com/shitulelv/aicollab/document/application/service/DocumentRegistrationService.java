package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文档受理入口（M1 迁移后按 MQ 总闸分流）。
 *
 * <p>MQ 模式（{@code MQ_ENABLED=true}）：上传受理走 RocketMQ <b>事务消息</b> ——
 * 半消息先发，注册事务在事务监听器内执行，提交后消息才对消费者可见；应用崩溃时
 * 由 broker 回查以文档状态行为事实源裁决。半消息发送失败不阻塞上传主流程：
 * 回落进程内注册 + 派发，即便派发也失败，恢复 Job（5 分钟对账）仍会兜底 ——
 * 这是"消息丢失无害"设计，状态行（project_document.status）本身就是事实源。</p>
 *
 * <p>回落模式（{@code MQ_ENABLED=false}）：与迁移前行为一致 —— 注册事务提交后
 * 经 AFTER_COMMIT 事件进程内派发。Redis 深化 P2 后派发前经 Redisson 跨实例互斥锁
 * 去重（多实例不再重复派发；正确性由 claim CAS 兜底，见 {@link DocumentTaskDispatcher}
 * 与 .env.example 说明）。</p>
 */
@Service
public class DocumentRegistrationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentRegistrationService.class);

    private final DocumentRegistrationTransaction registration;
    private final DocumentPipelineMessenger messenger;

    public DocumentRegistrationService(DocumentRegistrationTransaction registration,
                                       DocumentPipelineMessenger messenger) {
        this.registration = registration;
        this.messenger = messenger;
    }

    public void registerUploadedDocument(DocumentEntity document) {
        if (messenger.enabled() && messenger.trySendRegistrationTransaction(document,
                // 注册事务作为 MQ 本地事务执行；事务消息本身即派发通知，不再发布事件
                () -> registration.register(document, false))) {
            return;
        }
        // 回落路径：总闸关闭，或事务消息发送失败（broker 不可用）。
        // 注册事务提交后由 AFTER_COMMIT 事件触发进程内派发（MQ 模式下监听器会转投 MQ）。
        if (messenger.enabled()) {
            log.info("文档事务消息发送失败，回落进程内注册与派发，documentId={}", document.getId());
        }
        registration.register(document, true);
    }
}
