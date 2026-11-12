package com.shitulelv.aicollab.agent.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ai_job} / {@code ai_job_attempt} / {@code ai_job_result} 的唯一写入者（W5 冻结 §3.1/§3.2）。
 *
 * <p><b>并发纪律</b>：所有状态跃迁都用 {@code UPDATE ... WHERE version=? AND status=?}
 * （CAS）并 {@code RETURNING *}；调用方（{@link AiJobService}）负责“失败重读一次、
 * 二次失败抛冲突”。心跳额外要求 {@code lease_epoch} 匹配，用于 fence 迟到请求。</p>
 *
 * <p><b>结果幂等</b>：{@code (job_id, dedupe_key)} 唯一；{@code ON CONFLICT DO NOTHING}
 * 后返回已有行，重复 results 不重复入库。候选初始为 {@code PENDING_REVIEW}；
 * ACCEPTED/REJECTED 只能由管理员审核服务通过 CAS 写入，发布仍经
 * {@code AgentMemoryService} 的权限/审批边界。</p>
 *
 * <p>快照读取（{@link #projectFacts}/{@link #activeMemories}）只读、带 LIMIT，
 * 不写任何业务表。</p>
 */
@Repository
public class AiJobRepository {

    private static final int SNAPSHOT_MEMORY_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public AiJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // ai_job
    // ------------------------------------------------------------------

    public AiJobRecord create(UUID projectId, String jobType, String payloadJson,
            int maxAttempts, OffsetDateTime deadlineAt, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO ai_job (project_id, job_type, status, schema_version, payload,
                                    max_attempts, deadline_at, created_by)
                VALUES (?, 'MEMORY_EXTRACTION', 'QUEUED', 1, ?::jsonb, ?, ?, ?)
                RETURNING *
                """, jobMapper(), projectId, payloadJson == null ? "{}" : payloadJson,
                maxAttempts, deadlineAt, createdBy);
    }

    public Optional<AiJobRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM ai_job WHERE id=?", jobMapper(), id).stream().findFirst();
    }

    public AiJobRecord require(UUID id) {
        return find(id).orElseThrow(() -> new AiJobException(
                AiJobException.Kind.NOT_FOUND, "AI Job 不存在"));
    }

    /**
     * QUEUED → LEASED：attempt_count+1、lease_epoch+1、写租约 token/到期时间；
     * 只有队列条件满足时才成功（CAS version + status + next_attempt_at/deadline）。
     */
    public Optional<AiJobRecord> casLease(AiJobRecord current, UUID leaseToken,
            OffsetDateTime now, OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status='LEASED', attempt_count=attempt_count+1,
                       lease_epoch=lease_epoch+1, lease_token=?, lease_expires_at=?,
                       next_attempt_at=NULL, last_error_code=NULL,
                       version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status='QUEUED'
                   AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                   AND (deadline_at IS NULL OR deadline_at>?)
                 RETURNING *
                """, jobMapper(), leaseToken, leaseExpiresAt, now,
                current.id(), current.version(), now, now).stream().findFirst();
    }

    /**
     * 任意合法跃迁（CAS version + status + lease_epoch）。租约 token 在离开租约时清空；
     * {@code next_attempt_at} 由调用方给出（仅 FAILED_RETRYABLE 有意义）。
     */
    public Optional<AiJobRecord> casTransition(AiJobRecord current, AiJobStatus target,
            long leaseEpoch, String lastErrorCode, OffsetDateTime nextAttemptAt,
            OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status=?, last_error_code=?, lease_token=NULL, lease_expires_at=NULL,
                       next_attempt_at=?, version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status=? AND lease_epoch=?
                 RETURNING *
                """, jobMapper(), target.name(), lastErrorCode, nextAttemptAt, now,
                current.id(), current.version(), current.status().name(), leaseEpoch)
                .stream().findFirst();
    }

    /**
     * 心跳：CAS version + lease_epoch，只允许 LEASED/RUNNING；LEASED 首次心跳顺带进入 RUNNING。
     */
    public Optional<AiJobRecord> casHeartbeat(AiJobRecord current, long leaseEpoch,
            OffsetDateTime leaseExpiresAt, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status=CASE WHEN status='LEASED' THEN 'RUNNING' ELSE status END,
                       lease_expires_at=?, version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status IN ('LEASED','RUNNING') AND lease_epoch=?
                 RETURNING *
                """, jobMapper(), leaseExpiresAt, now,
                current.id(), current.version(), leaseEpoch).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // ai_job_attempt
    // ------------------------------------------------------------------

    public void insertAttempt(UUID jobId, int attemptNo, long leaseEpoch, OffsetDateTime startedAt) {
        jdbc.update("""
                INSERT INTO ai_job_attempt (job_id, attempt_no, status, lease_epoch, started_at)
                VALUES (?, ?, 'RUNNING', ?, ?)
                """, jobId, attemptNo, leaseEpoch, startedAt);
    }

    public void touchAttemptHeartbeat(UUID jobId, int attemptNo, long leaseEpoch,
            OffsetDateTime heartbeatAt) {
        jdbc.update("""
                UPDATE ai_job_attempt SET heartbeat_at=?
                 WHERE job_id=? AND attempt_no=? AND lease_epoch=? AND status='RUNNING'
                """, heartbeatAt, jobId, attemptNo, leaseEpoch);
    }

    public void finishAttempt(UUID jobId, int attemptNo, AiJobAttemptStatus status,
            String errorCode, OffsetDateTime finishedAt) {
        jdbc.update("""
                UPDATE ai_job_attempt SET status=?, error_code=?, finished_at=?
                 WHERE job_id=? AND attempt_no=?
                """, status.name(), errorCode, finishedAt, jobId, attemptNo);
    }

    // ------------------------------------------------------------------
    // ai_job_result
    // ------------------------------------------------------------------

    /** 结果写入结果：inserted=false 时 record 为已存在的同 dedupeKey 行。 */
    public record ResultWrite(boolean inserted, AiJobResultRecord record) {
    }

    public ResultWrite appendResult(UUID jobId, int attemptNo, String resultKind,
            String dedupeKey, String payloadJson, String snapshotHash,
            String snapshotRefsJson, OffsetDateTime now) {
        List<AiJobResultRecord> inserted = jdbc.query("""
                INSERT INTO ai_job_result (job_id, attempt_no, result_kind, dedupe_key, payload,
                                           review_status, snapshot_hash, snapshot_refs, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING_REVIEW', ?, ?::jsonb, ?)
                ON CONFLICT (job_id, dedupe_key) DO NOTHING
                RETURNING *
                """, resultMapper(), jobId, attemptNo, resultKind, dedupeKey, payloadJson,
                snapshotHash, snapshotRefsJson, now);
        if (!inserted.isEmpty()) {
            return new ResultWrite(true, inserted.getFirst());
        }
        AiJobResultRecord existing = jdbc.query(
                "SELECT * FROM ai_job_result WHERE job_id=? AND dedupe_key=?",
                resultMapper(), jobId, dedupeKey).stream().findFirst().orElse(null);
        return new ResultWrite(false, existing);
    }

    public int countResults(UUID jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id=?", Integer.class, jobId);
        return count == null ? 0 : count;
    }

    /**
     * 候选审核列表：只从 Job 项目边界内读取，调用方负责管理员权限校验。
     * {@code reviewStatus=null} 表示不过滤状态。
     */
    public List<AiJobResultRecord> listResults(UUID projectId, String reviewStatus, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        if (reviewStatus == null || reviewStatus.isBlank()) {
            return jdbc.query("""
                    SELECT r.* FROM ai_job_result r
                      JOIN ai_job j ON j.id=r.job_id
                     WHERE j.project_id=?
                     ORDER BY r.created_at DESC, r.id DESC LIMIT ?
                    """, resultMapper(), projectId, bounded);
        }
        return jdbc.query("""
                SELECT r.* FROM ai_job_result r
                  JOIN ai_job j ON j.id=r.job_id
                 WHERE j.project_id=? AND r.review_status=?
                 ORDER BY r.created_at DESC, r.id DESC LIMIT ?
                """, resultMapper(), projectId, reviewStatus, bounded);
    }

    /** 审核事务内的行锁；同时校验结果属于该项目，避免跨项目审批。 */
    public Optional<AiJobResultRecord> lockResultForReview(UUID projectId, UUID resultId) {
        return jdbc.query("""
                SELECT r.* FROM ai_job_result r
                  JOIN ai_job j ON j.id=r.job_id
                 WHERE r.id=? AND j.project_id=?
                 FOR UPDATE OF r
                """, resultMapper(), resultId, projectId).stream().findFirst();
    }

    public Optional<AiJobResultRecord> findResult(UUID projectId, UUID resultId) {
        return jdbc.query("""
                SELECT r.* FROM ai_job_result r
                  JOIN ai_job j ON j.id=r.job_id
                 WHERE r.id=? AND j.project_id=?
                """, resultMapper(), resultId, projectId).stream().findFirst();
    }

    /**
     * PENDING_REVIEW → ACCEPTED/REJECTED 的 CAS。调用方已持有行锁，仍用 from 状态兜底，
     * 防止未来调用路径绕过锁时重复发布。
     */
    public boolean markReviewed(UUID projectId, UUID resultId, String fromStatus, String toStatus) {
        return jdbc.update("""
                UPDATE ai_job_result r
                   SET review_status=?
                  FROM ai_job j
                 WHERE r.id=? AND r.job_id=j.id AND j.project_id=?
                   AND r.review_status=?
                """, toStatus, resultId, projectId, fromStatus) == 1;
    }

    /** 候选发布后的幂等来源：同项目、同来源类型的 source_id 唯一指向已发布记忆。 */
    public Optional<UUID> findMemoryBySource(UUID projectId, String sourceType, UUID sourceId) {
        return jdbc.query("""
                SELECT id FROM agent_memory
                 WHERE project_id=? AND source_type=? AND source_id=?
                 ORDER BY created_at ASC, id ASC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class),
                projectId, sourceType, sourceId).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // attempt snapshot
    // ------------------------------------------------------------------

    /** 已冻结的 attempt 快照；审批时按 jobId+attemptNo 读取，不依赖当前租约。 */
    public record AttemptSnapshot(UUID jobId, UUID projectId, int attemptNo, long leaseEpoch,
            String snapshotHash, String snapshotRefsJson, String payloadJson) {
    }

    public Optional<AttemptSnapshot> findAttemptSnapshot(UUID jobId, int attemptNo,
            long leaseEpoch) {
        return jdbc.query("""
                SELECT a.job_id, j.project_id, a.attempt_no, a.lease_epoch, a.snapshot_hash,
                       a.snapshot_refs::text AS snapshot_refs_json, a.snapshot_payload
                  FROM ai_job_attempt a
                  JOIN ai_job j ON j.id=a.job_id
                 WHERE a.job_id=? AND a.attempt_no=? AND a.lease_epoch=?
                   AND a.snapshot_hash IS NOT NULL
                """, attemptSnapshotMapper(), jobId, attemptNo, leaseEpoch).stream().findFirst();
    }

    public Optional<AttemptSnapshot> findAttemptSnapshot(UUID jobId, int attemptNo) {
        return jdbc.query("""
                SELECT a.job_id, j.project_id, a.attempt_no, a.lease_epoch, a.snapshot_hash,
                       a.snapshot_refs::text AS snapshot_refs_json, a.snapshot_payload
                  FROM ai_job_attempt a
                  JOIN ai_job j ON j.id=a.job_id
                 WHERE a.job_id=? AND a.attempt_no=? AND a.snapshot_hash IS NOT NULL
                """, attemptSnapshotMapper(), jobId, attemptNo).stream().findFirst();
    }

    /**
     * 首次读取时冻结快照。并发读只有一条 UPDATE 能写入；输家重新读取赢家的快照，
     * 保证同一 attempt 的所有调用看到同一 hash/refs/payload。
     */
    public Optional<AttemptSnapshot> saveAttemptSnapshotIfAbsent(UUID jobId, int attemptNo,
            long leaseEpoch, String snapshotHash, String snapshotRefsJson, String payloadJson) {
        List<AttemptSnapshot> inserted = jdbc.query("""
                UPDATE ai_job_attempt a
                   SET snapshot_hash=?, snapshot_refs=?::jsonb, snapshot_payload=?
                  FROM ai_job j
                 WHERE a.job_id=j.id AND a.job_id=? AND a.attempt_no=? AND a.lease_epoch=?
                   AND a.snapshot_hash IS NULL
                RETURNING a.job_id, j.project_id, a.attempt_no, a.lease_epoch, a.snapshot_hash,
                          a.snapshot_refs::text AS snapshot_refs_json, a.snapshot_payload
                """, attemptSnapshotMapper(), snapshotHash, snapshotRefsJson, payloadJson,
                jobId, attemptNo, leaseEpoch);
        if (!inserted.isEmpty()) {
            return Optional.of(inserted.getFirst());
        }
        return findAttemptSnapshot(jobId, attemptNo, leaseEpoch);
    }

    // ------------------------------------------------------------------
    // 恢复/重试扫描（只读；写仍走上面的 CAS）
    // ------------------------------------------------------------------

    /** 到期的 QUEUED Job；调度器逐条交给 dispatcher，具体租约仍由 CAS 决定。 */
    public List<AiJobRecord> findQueued(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE status='QUEUED'
                   AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                   AND (deadline_at IS NULL OR deadline_at>?)
                 ORDER BY created_at, id LIMIT ?
                """, jobMapper(), now, now, Math.max(1, Math.min(limit, 200)));
    }

    public List<AiJobRecord> findLeaseExpired(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE status IN ('LEASED','RUNNING')
                   AND lease_expires_at IS NOT NULL AND lease_expires_at < ?
                 ORDER BY lease_expires_at LIMIT ?
                """, jobMapper(), now, limit);
    }

    public List<AiJobRecord> findDeadlineExceeded(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE status IN ('QUEUED','LEASED','RUNNING','FAILED_RETRYABLE')
                   AND deadline_at IS NOT NULL AND deadline_at < ?
                 ORDER BY deadline_at LIMIT ?
                """, jobMapper(), now, limit);
    }

    public List<AiJobRecord> findRetryDue(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE status='FAILED_RETRYABLE'
                   AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?
                   AND (deadline_at IS NULL OR deadline_at > ?)
                 ORDER BY next_attempt_at LIMIT ?
                """, jobMapper(), now, now, limit);
    }

    // ------------------------------------------------------------------
    // 有界快照读取（只读；不写业务表）
    // ------------------------------------------------------------------

    public record ProjectFacts(String name, String description) {
    }

    public Optional<ProjectFacts> projectFacts(UUID projectId) {
        return jdbc.query("SELECT name, description FROM project WHERE id=?",
                (rs, row) -> new ProjectFacts(rs.getString(1), rs.getString(2)), projectId)
                .stream().findFirst();
    }

    public record MemoryFact(UUID id, String type, String title, String content) {
    }

    /** 活跃且未被替代的记忆（只读；最多 50 条，供快照 existingMemories）。 */
    public List<MemoryFact> activeMemories(UUID projectId, int limit) {
        int bounded = Math.max(1, Math.min(limit, SNAPSHOT_MEMORY_LIMIT));
        return jdbc.query("""
                SELECT id, type, title, content FROM agent_memory
                 WHERE project_id=? AND status='ACTIVE' AND superseded_by IS NULL
                 ORDER BY updated_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new MemoryFact(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
                projectId, bounded);
    }

    // ------------------------------------------------------------------
    // row mappers
    // ------------------------------------------------------------------

    private static RowMapper<AiJobRecord> jobMapper() {
        return AiJobRepository::job;
    }

    private static AiJobRecord job(ResultSet rs, int row) throws SQLException {
        return new AiJobRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("job_type"),
                AiJobStatus.from(rs.getString("status")),
                rs.getInt("schema_version"),
                rs.getString("payload"),
                rs.getInt("max_attempts"),
                rs.getInt("attempt_count"),
                rs.getLong("lease_epoch"),
                rs.getObject("lease_token", UUID.class),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getInt("version"));
    }

    private static RowMapper<AiJobResultRecord> resultMapper() {
        return (rs, row) -> new AiJobResultRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("result_kind"),
                rs.getString("dedupe_key"),
                rs.getString("payload"),
                rs.getString("review_status"),
                rs.getString("snapshot_hash"),
                rs.getString("snapshot_refs"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static RowMapper<AttemptSnapshot> attemptSnapshotMapper() {
        return (rs, row) -> new AttemptSnapshot(
                rs.getObject("job_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getLong("lease_epoch"),
                rs.getString("snapshot_hash"),
                rs.getString("snapshot_refs_json"),
                rs.getString("snapshot_payload"));
    }
}
