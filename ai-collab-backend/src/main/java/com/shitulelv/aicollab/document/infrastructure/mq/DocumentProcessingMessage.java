package com.shitulelv.aicollab.document.infrastructure.mq;

import org.apache.rocketmq.common.message.MessageExt;

import java.util.UUID;

/**
 * 文档处理消息体（文档链路唯一的业务消息形态）。
 *
 * <p>身份信息同时写入消息属性与 JSON 正文：消费与回查优先读属性（免反序列化、
 * 可直接用于路由键），正文保留完整结构便于排查与后续阶段扩展。</p>
 *
 * @param projectId  项目 ID（与 documentId 共同构成文档身份键）
 * @param documentId 文档 ID
 * @param stage      处理阶段（当前仅 parse；未来索引细分阶段复用同一 topic 时扩展）
 */
public record DocumentProcessingMessage(UUID projectId, UUID documentId, String stage) {

    /** 解析阶段：文档受理 / 重试 / 批量重建索引共用的唯一在用阶段。 */
    public static final String STAGE_PARSE = "parse";

    public static final String PROP_PROJECT_ID = "projectId";
    public static final String PROP_DOCUMENT_ID = "documentId";
    public static final String PROP_STAGE = "stage";

    public DocumentProcessingMessage {
        if (stage == null || stage.isBlank()) stage = STAGE_PARSE;
    }

    public static DocumentProcessingMessage parse(UUID projectId, UUID documentId) {
        return new DocumentProcessingMessage(projectId, documentId, STAGE_PARSE);
    }

    /** 从 RocketMQ 消息属性恢复身份信息；属性缺失或非法视为不可消费，抛出参数异常。 */
    public static DocumentProcessingMessage fromProperties(MessageExt message) {
        String projectId = message.getUserProperty(PROP_PROJECT_ID);
        String documentId = message.getUserProperty(PROP_DOCUMENT_ID);
        String stage = message.getUserProperty(PROP_STAGE);
        if (projectId == null || documentId == null) {
            throw new IllegalArgumentException(
                    "文档消息缺少身份属性，messageId=" + message.getMsgId());
        }
        return new DocumentProcessingMessage(UUID.fromString(projectId), UUID.fromString(documentId), stage);
    }
}
