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
     * @param retentionHours 崩溃残留的最长保留时长（正常路径完成即释放，正确性由状态机兜底）
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
     * 释放处理中占位：本次消费结束（成功、业务失败或系统异常）即调用，
     * 让后续重试 / 重建索引消息可以重新受理。释放失败无害 —— 完成后的重复
     * 投递仍会被状态前置校验与 claim CAS 拦截。
     */
    @Delete("""
            DELETE FROM idempotency_record
            WHERE user_id = #{userId} AND endpoint = #{endpoint} AND idempotency_key = #{documentId}
            """)
    int delete(@Param("userId") UUID userId, @Param("endpoint") String endpoint,
               @Param("documentId") UUID documentId);
}
