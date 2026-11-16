package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 文档处理消息的消费处理器（M1 迁移）。
 *
 * <p>消费幂等保障（顺序执行，任一命中即 ACK 丢弃，保证处理副作用恰好一次）：</p>
 * <ol>
 *   <li>状态前置校验：文档状态行不是 UPLOADED（不存在 / PARSING / INDEXING / 终态）
 *       即为重复投递或非法跃迁，直接丢弃；</li>
 *   <li>处理中占位：{@code idempotency_record} 条件插入（UNIQUE 冲突 = 已有实例受理），
 *       多实例并发重复投递时只有一例获得处理权；本次处理结束（成功、业务失败或系统
 *       异常）即释放，不承担完成后的长期去重 —— 完成后的重复消息由状态前置校验与
 *       claim CAS 拦截；</li>
 *   <li>状态机 CAS：共享执行路径内部 {@code DocumentRepository.claim} 以条件更新
 *       （UPLOADED→PARSING）完成最终仲裁，非法跃迁天然失败。</li>
 * </ol>
 *
 * <p>系统级异常（如数据库瞬时不可用）在释放占位后向上抛出，由消费者侧转
 * RECONSUME_LATER 重试；业务失败（解析失败、嵌入失败等）由
 * {@code DocumentProcessingService} 内部标记 FAILED 后正常返回 —— 与进程内路径语义一致。
 * 由于占位在本次处理结束后即释放，用户手动重试或重建索引触发的新消息可以立即重新受理。</p>
 */
@Component
public class DocumentProcessingMqHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingMqHandler.class);

    private final DocumentRepository documents;
    private final DocumentMqIdempotencyRepository idempotency;
    private final DocumentTaskDispatcher dispatcher;
    private final DocumentMqMetrics metrics;
    private final DocumentMqProperties properties;

    public DocumentProcessingMqHandler(DocumentRepository documents,
                                       DocumentMqIdempotencyRepository idempotency,
                                       DocumentTaskDispatcher dispatcher,
                                       DocumentMqMetrics metrics,
                                       DocumentMqProperties properties) {
        this.documents = documents;
        this.idempotency = idempotency;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * 消费一条文档处理消息。正常返回 = ACK；抛出 RuntimeException = RECONSUME_LATER。
     */
    public void handle(DocumentProcessingMessage message) {
        Optional<DocumentEntity> found = documents.find(message.projectId(), message.documentId());
        // 保障一：状态前置校验 —— 重复投递 / 非法跃迁直接 ACK 丢弃
        if (found.isEmpty() || found.get().getStatus() != DocumentStatus.UPLOADED) {
            log.info("文档消息幂等丢弃（状态前置校验）：documentId={}，status={}",
                    message.documentId(), found.map(DocumentEntity::getStatus).orElse(null));
            metrics.consumed("dropped");
            return;
        }
        DocumentEntity document = found.get();
        String endpoint = properties.idempotencyEndpoint(message.stage());
        // 保障二：幂等占位（idempotency_record UNIQUE 冲突 = 已有实例受理）
        if (!idempotency.tryAcquire(document.getUploadedBy(), endpoint,
                message.projectId(), message.documentId(), message.stage())) {
            log.info("文档消息幂等丢弃（幂等占位冲突）：documentId={}，stage={}",
                    message.documentId(), message.stage());
            metrics.consumed("dropped");
            return;
        }
        try {
            // 保障三：共享执行路径内部的 claim CAS（UPLOADED→PARSING 条件更新）最终仲裁
            dispatcher.runOnce(message.projectId(), message.documentId());
            metrics.consumed("ok");
        } catch (RuntimeException exception) {
            metrics.failed(message.stage());
            throw exception;
        } finally {
            // 处理结束即释放占位：成功 / 业务失败 / 系统异常都不得留下长期阻塞；
            // 释放失败只记录日志，不改变 ACK 或原始异常传播。
            releaseQuietly(document.getUploadedBy(), endpoint, message.documentId());
        }
    }

    private void releaseQuietly(UUID uploaderId, String endpoint, UUID documentId) {
        try {
            if (!idempotency.release(uploaderId, endpoint, documentId)) {
                log.warn("幂等处理占位释放失败（记录可能已过期或已被清理），documentId={}", documentId);
            }
        } catch (RuntimeException releaseFailure) {
            log.warn("幂等处理占位释放异常，将由恢复 Job 对账兜底，documentId={}", documentId, releaseFailure);
        }
    }
}
