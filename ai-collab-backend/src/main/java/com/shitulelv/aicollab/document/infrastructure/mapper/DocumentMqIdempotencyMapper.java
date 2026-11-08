package com.shitulelv.aicollab.document.infrastructure.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * MQ 消费幂等 Mapper（M1 迁移）：复用 V1 建立的 {@code idempotency_record} 表
 * （UNIQUE(user_id, endpoint, idempotency_key)），无新增表/列。
 * SQL 使用条件插入（ON CONFLICT DO NOTHING），冲突返回 0 行即"已有实例受理"。
 */
@Mapper
public interface DocumentMqIdempotencyMapper {

    /**
     * 条件插入幂等占位记录。
     *
     * @param userId         文档上传者（表结构要求非空外键，文档行本身持有该引用）
     * @param endpoint       幂等端点：mq:document-processing:&lt;stage&gt;
     * @param documentId     幂等键 = 文档 ID
     * @param requestHash    sha256(projectId:documentId:stage)，满足 char(64) 非空约束
     * @param retentionHours 记录保留时长（到期由既有清理语义自然失效，正确性由状态机兜底）
     * @return 1 = 占位成功（本实例获得处理权）；0 = 冲突（重复投递，直接丢弃）
     */
    @Insert("""
            INSERT INTO idempotency_record
                (user_id, endpoint, idempotency_key, request_hash, response_json, created_at, expires_at)
            VALUES (#{userId}, #{endpoint}, #{documentId}, #{requestHash}, '{}'::jsonb,
                    now(), now() + make_interval(hours => #{retentionHours}))
            ON CONFLICT (user_id, endpoint, idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("endpoint") String endpoint,
                       @Param("documentId") UUID documentId, @Param("requestHash") String requestHash,
                       @Param("retentionHours") int retentionHours);

    /**
     * 撤销幂等占位：消费发生系统级异常（重试仍有意义）时释放处理权，
     * 让重试投递可以重新受理。撤销失败无害 —— 状态行仍是 UPLOADED，
     * 恢复 Job 会重新派发。
     */
    @Delete("""
            DELETE FROM idempotency_record
            WHERE user_id = #{userId} AND endpoint = #{endpoint} AND idempotency_key = #{documentId}
            """)
    int delete(@Param("userId") UUID userId, @Param("endpoint") String endpoint,
               @Param("documentId") UUID documentId);
}
