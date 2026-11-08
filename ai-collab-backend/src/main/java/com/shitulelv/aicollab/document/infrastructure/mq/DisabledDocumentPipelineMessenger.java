package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentPipelineMessenger;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;

import java.util.UUID;

/**
 * MQ 总闸关闭（{@code document.mq.enabled=false}）时的回落实现：
 * {@link #enabled()} 恒为 false，调用方据此前提走进程内派发路径；两个发送方法
 * 在该语义下不应被调用，被调用即抛出（fail-fast 暴露装配错误）。
 */
public class DisabledDocumentPipelineMessenger implements DocumentPipelineMessenger {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean trySendRegistrationTransaction(DocumentEntity document, Runnable localRegistration) {
        throw new IllegalStateException("文档链路 MQ 总闸未开启，不应调用事务消息发送");
    }

    @Override
    public void sendProcessingNotification(UUID projectId, UUID documentId) {
        throw new IllegalStateException("文档链路 MQ 总闸未开启，不应调用普通消息发送");
    }
}
