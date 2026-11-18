package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 文档处理消息消费处理器。
 *
 * <p>状态预检只用于 ACK 已无可执行工作的消息；并发认领由共享处理路径中的数据库
 * {@code UPLOADED→PARSING} 条件更新完成。处理令牌与心跳阻止旧处理代次覆盖恢复后的结果。</p>
 *
 * <p>claim 前系统异常向上抛出，由消费者转为 RECONSUME_LATER。进程在 claim 前崩溃不会
 * 留下消息外的占位，重投可以再次进入处理；消息丢失或重试耗尽时，恢复 Job 会从仍为
 * UPLOADED 的状态行重新派发。业务失败由 {@code DocumentProcessingService} 标记 FAILED，
 * 用户重试或重建索引会重置状态并发布新通知。</p>
 */
@Component
public class DocumentProcessingMqHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingMqHandler.class);

    private final DocumentRepository documents;
    private final DocumentTaskDispatcher dispatcher;
    private final DocumentMqMetrics metrics;

    public DocumentProcessingMqHandler(DocumentRepository documents,
                                       DocumentTaskDispatcher dispatcher,
                                       DocumentMqMetrics metrics) {
        this.documents = documents;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    /** 消费一条文档处理消息。正常返回 = ACK；抛出 RuntimeException = RECONSUME_LATER。 */
    public void handle(DocumentProcessingMessage message) {
        Optional<DocumentEntity> found = documents.find(message.projectId(), message.documentId());
        if (found.isEmpty() || found.get().getStatus() != DocumentStatus.UPLOADED) {
            log.info("文档消息状态已前进或记录不存在，ACK 丢弃：documentId={}，status={}",
                    message.documentId(), found.map(DocumentEntity::getStatus).orElse(null));
            metrics.consumed("dropped");
            return;
        }
        // 并发消息与恢复派发共享数据库 claim CAS，只有一个调用可以认领文档。
        // 异常直接交给 listener 统一记失败指标并返回 RECONSUME_LATER。
        dispatcher.runOnce(message.projectId(), message.documentId());
        metrics.consumed("ok");
    }
}
