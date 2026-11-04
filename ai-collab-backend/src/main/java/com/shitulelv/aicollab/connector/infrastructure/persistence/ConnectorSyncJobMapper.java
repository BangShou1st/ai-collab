package com.shitulelv.aicollab.connector.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ConnectorSyncJobMapper {
    @Insert("""
            INSERT INTO connector_sync_job (id, source_id, trigger_type, status, lease_expires_at, started_at)
            VALUES (#{id}::uuid, #{sourceId}::uuid, #{triggerType}, 'RUNNING',
                now() + INTERVAL '15 minutes', now())
            """)
    int insertRunning(@Param("id") UUID id, @Param("sourceId") UUID sourceId,
                      @Param("triggerType") String triggerType);

    @Select("""
            SELECT id, source_id, trigger_type, status, started_at, finished_at,
                   scanned_count, updated_count, skipped_count, failed_count, removed_count, error_summary
            FROM connector_sync_job WHERE source_id=#{sourceId}::uuid AND status='RUNNING'
            """)
    ConnectorRows.JobRow findRunning(@Param("sourceId") UUID sourceId);

    @Select("""
            SELECT id, source_id, trigger_type, status, started_at, finished_at,
                   scanned_count, updated_count, skipped_count, failed_count, removed_count, error_summary
            FROM connector_sync_job WHERE id=#{id}::uuid
            """)
    ConnectorRows.JobRow find(@Param("id") UUID id);

    @Select("""
            SELECT id, source_id, trigger_type, status, started_at, finished_at,
                   scanned_count, updated_count, skipped_count, failed_count, removed_count, error_summary
            FROM connector_sync_job WHERE source_id=#{sourceId}::uuid
            ORDER BY started_at DESC LIMIT #{limit}
            """)
    List<ConnectorRows.JobRow> listRecent(@Param("sourceId") UUID sourceId, @Param("limit") int limit);

    @Update("""
            UPDATE connector_sync_job SET lease_expires_at = now() + INTERVAL '15 minutes'
            WHERE id=#{id}::uuid AND status='RUNNING' AND lease_expires_at > now()
            """)
    int renewLease(@Param("id") UUID id);

    /**
     * Fencing 门：以行锁锁定任务并校验所有权（RUNNING 且租约未过期）。
     * 返回 1 表示锁已持有、所有权成立（锁保持到当前事务提交/回滚）；
     * 返回 null 表示任务已不属于本 Worker——必须放弃受保护的数据库写入。
     * 过期但尚未被回收的租约同样不能通过（lease_expires_at > now()），旧 Worker 无法复活任务。
     */
    @Select("""
            SELECT 1 FROM connector_sync_job
            WHERE id=#{id}::uuid AND status='RUNNING' AND lease_expires_at > now()
            FOR UPDATE
            """)
    Integer lockIfOwned(@Param("id") UUID id);

    @Update("""
            UPDATE connector_sync_job SET status=#{status}, finished_at=now(),
                scanned_count=#{scanned}, updated_count=#{updated}, skipped_count=#{skipped},
                failed_count=#{failed}, removed_count=#{removed}, error_summary=#{errorSummary}
            WHERE id=#{id}::uuid AND status='RUNNING'
            """)
    int finish(@Param("id") UUID id, @Param("status") String status,
               @Param("scanned") int scanned, @Param("updated") int updated,
               @Param("skipped") int skipped, @Param("failed") int failed,
               @Param("removed") int removed, @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE connector_sync_job SET status='FAILED', finished_at=now(),
                error_summary=COALESCE(error_summary,'') || '同步超时，运行租约过期'
            WHERE id=#{id}::uuid AND status='RUNNING' AND lease_expires_at < now()
            """)
    int expireStale(@Param("id") UUID id);
}
