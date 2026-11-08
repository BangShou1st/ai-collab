package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.infrastructure.mapper.DocumentMqIdempotencyMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * MQ 消费幂等仓储（M1 迁移）。
 *
 * <p>复用 V1 的 {@code idempotency_record} 表与既有 UNIQUE(user_id, endpoint,
 * idempotency_key) 语义：幂等键 = documentId，处理阶段编码进 endpoint
 * （{@code mq:document-processing:<stage>}），即任务书所述 key = documentId + stage。
 * user_id 使用文档上传者（表结构要求非空 app_user 外键，文档行本身持有该引用）。</p>
 *
 * <p>该记录只是消费幂等三重保障中的第二重（第一重为状态前置校验、第三重为
 * 状态机 CAS 条件更新）；记录 24 小时过期，过期后的重复投递由状态校验与
 * CAS 兜底，不依赖记录本身的存在。</p>
 */
@Repository
public class DocumentMqIdempotencyRepository {

    /** 记录保留时长（小时）：覆盖重试窗口 + 恢复 Job 对账周期，过期即由状态机兜底。 */
    private static final int RETENTION_HOURS = 24;

    private final DocumentMqIdempotencyMapper mapper;

    public DocumentMqIdempotencyRepository(DocumentMqIdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 尝试占用处理权：UNIQUE 冲突返回 false（重复投递，消费方直接 ACK 丢弃）。
     */
    public boolean tryAcquire(UUID uploaderId, String endpoint, UUID projectId,
                              UUID documentId, String stage) {
        return mapper.insertIfAbsent(uploaderId, endpoint, documentId,
                requestHash(projectId, documentId, stage), RETENTION_HOURS) == 1;
    }

    /**
     * 释放处理权（系统级异常后的重试仍有意义）；返回 false 表示撤销失败，
     * 此时依赖恢复 Job 对账兜底，不影响正确性。
     */
    public boolean release(UUID uploaderId, String endpoint, UUID documentId) {
        return mapper.delete(uploaderId, endpoint, documentId) == 1;
    }

    /** sha256(projectId:documentId:stage)：满足 request_hash char(64) 非空约束的指纹。 */
    static String requestHash(UUID projectId, UUID documentId, String stage) {
        String material = projectId + ":" + documentId + ":" + stage;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            // JVM 必须提供 SHA-256；走到这里属于运行环境异常，直接终止消费
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
