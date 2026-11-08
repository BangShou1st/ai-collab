package com.shitulelv.aicollab.document.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentPipelineMessenger;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.mq.RocketMqProducerLifecycle;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * RocketMQ 实现的文档链路消息门面（M1 迁移，{@code document.mq.enabled=true} 时装配）。
 *
 * <p>客户端选型说明：Spring Boot 4.1 下不使用 {@code rocketmq-spring-boot-starter}
 * （其自动配置尚未适配 Boot 4，RocketMQTemplate 无法注册，见 apache/rocketmq-spring#761），
 * 改为 rocketmq-client（remoting 协议，与服务端 5.3.3 同版本）薄封装。</p>
 *
 * <p>生产者懒启动：{@code DefaultMQProducer.start()} 失败后实例不可复用（内部状态机
 * 进入 START_FAILED），因此每次重试都重建实例；启动失败不抛出到调用方 —— 上传主流程
 * 回落进程内路径，恢复 Job 对账兜底（消息丢失无害设计）。</p>
 */
public class RocketMqDocumentPipelineMessenger implements DocumentPipelineMessenger {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDocumentPipelineMessenger.class);

    private final DocumentMqProperties properties;
    private final DocumentRepository documents;
    private final DocumentMqMetrics metrics;
    private final ObjectMapper objectMapper;
    private final DocumentRegistrationTransactionListener transactionListener;

    /**
     * 生产者生命周期（M2 提升到公共层 {@link RocketMqProducerLifecycle}）：懒启动、
     * START_FAILED 后重建重试，本类只保留文档链路自己的消息体与发送语义。
     */
    private final RocketMqProducerLifecycle producerLifecycle;

    public RocketMqDocumentPipelineMessenger(DocumentMqProperties properties, DocumentRepository documents,
                                             DocumentMqMetrics metrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.documents = documents;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionListener = new DocumentRegistrationTransactionListener(documents, metrics);
        this.producerLifecycle = new RocketMqProducerLifecycle(
                properties.producerGroup(), properties.namesrvAddr(), "文档链路");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean trySendRegistrationTransaction(DocumentEntity document, Runnable localRegistration) {
        var active = producerLifecycle.acquire();
        if (active == null) {
            return false;
        }
        DocumentRegistrationExecution execution = new DocumentRegistrationExecution(localRegistration);
        try {
            TransactionSendResult result = active.sendMessageInTransaction(
                    toMessage(document), execution);
            if (execution.error != null) {
                // 注册业务/未预期异常：半消息已随 ROLLBACK 丢弃，异常按原语义透传调用方
                metrics.sent("failed");
                throw execution.error;
            }
            LocalTransactionState state = result == null ? null : result.getLocalTransactionState();
            if (state != LocalTransactionState.COMMIT_MESSAGE) {
                // 未提交也未回滚（UNKNOW 等罕见状态）：回查会以状态行裁决；对调用方按
                // 发送失败处理并回落进程内路径 —— 若事务实际已提交，重复派发被消费幂等拦截
                log.warn("文档事务消息未确认提交（state={}），回落进程内路径，documentId={}",
                        state, document.getId());
                metrics.sent("unknown");
                return false;
            }
            metrics.sent("ok");
            return true;
        } catch (Exception exception) {
            if (execution.error == exception) {
                // 注册事务的异常恒为 RuntimeException，重抛无需方法签名声明
                throw execution.error;
            }
            // 半消息发送失败（broker 不可用等）：注册事务尚未执行，回落进程内路径
            log.warn("文档事务消息发送失败，回落进程内路径（恢复 Job 兜底），documentId={}，type={}",
                    document.getId(), exception.getClass().getSimpleName());
            metrics.sent("failed");
            return false;
        }
    }

    @Override
    public void sendProcessingNotification(UUID projectId, UUID documentId) {
        var active = producerLifecycle.acquire();
        if (active == null) {
            metrics.sent("failed");
            return;
        }
        try {
            DocumentProcessingMessage payload = DocumentProcessingMessage.parse(projectId, documentId);
            Message message = new Message(properties.topic(),
                    documentId.toString(),
                    objectMapper.writeValueAsBytes(payload));
            applyIdentityProperties(message, payload);
            SendResult result = active.send(message);
            metrics.sent("ok");
            log.debug("文档处理通知已发送，documentId={}，status={}", documentId, result.getSendStatus());
        } catch (Exception exception) {
            // 消息丢失无害：状态行仍是 UPLOADED，恢复 Job（5 分钟对账）会重新派发
            log.warn("文档处理通知发送失败，等待恢复 Job 对账兜底，projectId={}，documentId={}，type={}",
                    projectId, documentId, exception.getClass().getSimpleName());
            metrics.sent("failed");
        }
    }

    /** 销毁生产者（Spring 容器关闭时由配置类调用）。 */
    public void shutdown() {
        producerLifecycle.shutdown();
    }

    private Message toMessage(DocumentEntity document) throws Exception {
        DocumentProcessingMessage payload = DocumentProcessingMessage.parse(
                document.getProjectId(), document.getId());
        Message message = new Message(properties.topic(),
                document.getId().toString(),
                objectMapper.writeValueAsBytes(payload));
        applyIdentityProperties(message, payload);
        return message;
    }

    private void applyIdentityProperties(Message message, DocumentProcessingMessage payload) {
        message.putUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID,
                payload.projectId().toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID,
                payload.documentId().toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_STAGE, payload.stage());
    }
}
