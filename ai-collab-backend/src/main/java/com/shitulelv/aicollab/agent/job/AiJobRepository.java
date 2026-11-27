package com.shitulelv.aicollab.agent.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    /**
     * 三类索引任务（P2 契约 §4.2）。{@code projectId} 只有 {@code REINDEX_GENERATION} 可为空，由 V105
     * {@code ck_ai_job_project_bound} 兜底；系统任务 {@code created_by} 为空。
     */
    public AiJobRecord createIndexJob(String jobType, UUID projectId, UUID documentId, UUID generationId,
            UUID parentJobId, int maxAttempts) {
        if (!AiJobTypes.INDEX_DOCUMENT.equals(jobType) && !AiJobTypes.DELETE_DOCUMENT_INDEX.equals(jobType)
                && !AiJobTypes.REINDEX_GENERATION.equals(jobType)) {
            throw new IllegalArgumentException("不是索引任务类型: " + jobType);
        }
        return jdbc.queryForObject("""
                INSERT INTO ai_job (project_id, job_type, status, schema_version, payload,
                                    max_attempts, document_id, generation_id, parent_job_id)
                VALUES (?, ?, 'QUEUED', 1, '{}'::jsonb, ?, ?, ?, ?)
                RETURNING *
                """, jobMapper(), projectId, jobType, maxAttempts, documentId, generationId, parentJobId);
    }

    public AiJobRecord createReindexJob(UUID embeddingConfigId, int maxAttempts) {
        return jdbc.queryForObject("""
                INSERT INTO ai_job (job_type, status, schema_version, payload, max_attempts)
                VALUES ('REINDEX_GENERATION', 'QUEUED', 1,
                        jsonb_build_object('embeddingConfigId', CAST(? AS uuid)), ?)
                RETURNING *
                """, jobMapper(), embeddingConfigId, maxAttempts);
    }

    public Optional<UUID> findReindexEmbeddingConfig(UUID jobId) {
        return jdbc.query("""
                SELECT (payload->>'embeddingConfigId')::uuid FROM ai_job
                WHERE id=? AND job_type='REINDEX_GENERATION' AND payload->>'embeddingConfigId' IS NOT NULL
                """,
                (rs, row) -> rs.getObject(1, UUID.class), jobId).stream().findFirst();
    }

    public Optional<UUID> findEmbeddingConfigForGeneration(UUID generationId) {
        return jdbc.query("""
                SELECT (payload->>'embeddingConfigId')::uuid FROM ai_job
                WHERE job_type='REINDEX_GENERATION' AND payload->>'embeddingConfigId' IS NOT NULL
                  AND ((generation_id=? AND index_phase IN ('FANNED_OUT', 'ACTIVATED'))
                       OR (id=? AND status IN ('QUEUED', 'LEASED', 'RUNNING', 'FAILED_RETRYABLE')))
                """, (rs, row) -> rs.getObject(1, UUID.class), generationId, generationId).stream().findFirst();
    }

    /** 重建父任务下的子任务按状态计数（契约 §4.2 第 4、5 步）。 */
    public Map<AiJobStatus, Integer> childStatusCounts(UUID parentJobId) {
        Map<AiJobStatus, Integer> counts = new java.util.EnumMap<>(AiJobStatus.class);
        jdbc.query("SELECT status, count(*) AS n FROM ai_job WHERE parent_job_id=? GROUP BY status",
                rs -> {
                    counts.put(AiJobStatus.from(rs.getString("status")), rs.getInt("n"));
                }, parentJobId);
        return counts;
    }

    /** 找出某个类型、某个状态的任务（例如 RUNNING 的 REINDEX_GENERATION 父任务）。 */
    public List<AiJobRecord> findByTypeAndStatus(String jobType, AiJobStatus status, int limit) {
        return jdbc.query("SELECT * FROM ai_job WHERE job_type=? AND status=? ORDER BY created_at LIMIT ?",
                jobMapper(), jobType, status.name(), limit);
    }

    /**
     * P2-R5 R5-2：找出已完成创建 generation 并扇出子任务、尚待推进激活或放弃的重建父任务。
     * 命中 V105 部分索引 {@code idx_ai_job_reindex_phase}。
     */
    public List<AiJobRecord> findReindexFannedOut(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE job_type='REINDEX_GENERATION' AND status='SUCCEEDED' AND index_phase='FANNED_OUT'
                 ORDER BY updated_at, id LIMIT ?
                """, jobMapper(), bounded);
    }

    /**
     * P2-R5 R5-2：{@code REINDEX_GENERATION} 收到 result（generationId）时同事务置 {@code SUCCEEDED}、
     * {@code generation_id} 与 {@code index_phase='FANNED_OUT'}。
     */
    public Optional<AiJobRecord> casCompleteReindex(AiJobRecord current, long leaseEpoch,
            UUID generationId, String indexPhase, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status='SUCCEEDED', last_error_code=NULL,
                       lease_expires_at=NULL, next_attempt_at=NULL,
                       generation_id=?, index_phase=?,
                       version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status=? AND lease_epoch=?
                   AND job_type='REINDEX_GENERATION'
                 RETURNING *
                """, jobMapper(), generationId, indexPhase, now,
                current.id(), current.version(), current.status().name(), leaseEpoch)
                .stream().findFirst();
    }

    /**
     * P2-R5 R5-2：补偿扫描推进 {@code REINDEX_GENERATION} 的阶段（{@code FANNED_OUT -> ACTIVATED/ABANDONED}），
     * 带 {@code version + index_phase} CAS 防止并发重复推进。
     */
    public Optional<AiJobRecord> casUpdateIndexPhase(UUID jobId, int expectedVersion,
            String expectedPhase, String targetPhase, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET index_phase=?, version=version+1, updated_at=?
                 WHERE id=? AND job_type='REINDEX_GENERATION' AND status='SUCCEEDED'
                   AND version=? AND index_phase=?
                 RETURNING *
                """, jobMapper(), targetPhase, now, jobId, expectedVersion, expectedPhase)
                .stream().findFirst();
    }

    public AiJobRecord require(UUID id) {
        return find(id).orElseThrow(() -> new AiJobException(
                AiJobException.Kind.NOT_FOUND, "AI Job 不存在"));
    }

    /**
     * P1 认领（契约 §4.3）：条件更新 {@code status='QUEUED' AND lease_epoch=:epoch} → LEASED。
     * {@code attempt_count+1}（快照的 attempt 从 1 起），{@code lease_epoch} 不变——epoch 只在
     * 补偿扫描收回租约时 +1，Stream 消息里的 epoch 因此在整个 attempt 内保持有效。
     * 单条 UPDATE 即原子；并发认领只有一个能拿到行。
     */
    public Optional<AiJobRecord> casClaim(UUID jobId, long epoch, UUID leaseToken,
            OffsetDateTime now, OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status='LEASED', attempt_count=attempt_count+1,
                       lease_token=?, lease_expires_at=?,
                       next_attempt_at=NULL, last_error_code=NULL,
                       version=version+1, updated_at=?
                 WHERE id=? AND status='QUEUED' AND lease_epoch=?
                   AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                   AND (deadline_at IS NULL OR deadline_at>?)
                 RETURNING *
                """, jobMapper(), leaseToken, leaseExpiresAt, now,
                jobId, epoch, now, now).stream().findFirst();
    }

    /**
     * 租约内的合法跃迁（CAS version + status + lease_epoch）。{@code lease_token} 保留，
     * 用于 result/fail 在终态上的幂等判定（同 epoch + 同 leaseToken → 200 不变）；
     * 离开 LEASED/RUNNING 时清空 {@code lease_expires_at}。
     */
    public Optional<AiJobRecord> casTransition(AiJobRecord current, AiJobStatus target,
            long leaseEpoch, String lastErrorCode, OffsetDateTime nextAttemptAt,
            OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status=?, last_error_code=?,
                       lease_expires_at=CASE WHEN ? THEN lease_expires_at ELSE NULL END,
                       next_attempt_at=?, version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status=? AND lease_epoch=?
                 RETURNING *
                """, jobMapper(), target.name(), lastErrorCode, target.leased(), nextAttemptAt, now,
                current.id(), current.version(), current.status().name(), leaseEpoch)
                .stream().findFirst();
    }

    /**
     * 心跳：条件 {@code lease_epoch + lease_token}，只允许 LEASED/RUNNING；LEASED 首次心跳进入 RUNNING。
     */
    public Optional<AiJobRecord> casHeartbeat(UUID jobId, long leaseEpoch, UUID leaseToken,
            OffsetDateTime leaseExpiresAt, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status=CASE WHEN status='LEASED' THEN 'RUNNING' ELSE status END,
                       lease_expires_at=?, version=version+1, updated_at=?
                 WHERE id=? AND status IN ('LEASED','RUNNING') AND lease_epoch=? AND lease_token=?
                 RETURNING *
                """, jobMapper(), leaseExpiresAt, now,
                jobId, leaseEpoch, leaseToken).stream().findFirst();
    }

    /**
     * 补偿扫描的"围栏"跃迁（契约 §4.4 规则 2/3/4）：{@code lease_epoch+1} 并清空租约，
     * 让旧 epoch 的所有回调都变成 409。CAS version + status，两个扫描器并发时只有一个成功。
     */
    public Optional<AiJobRecord> casFence(AiJobRecord current, AiJobStatus target,
            String lastErrorCode, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job
                   SET status=?, lease_epoch=lease_epoch+1, lease_token=NULL, lease_expires_at=NULL,
                       next_attempt_at=NULL, last_error_code=COALESCE(?, last_error_code),
                       version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status=?
                 RETURNING *
                """, jobMapper(), target.name(), lastErrorCode, now,
                current.id(), current.version(), current.status().name()).stream().findFirst();
    }

    /** 规则 1：QUEUED 长时间未被认领时触碰 {@code updated_at}（CAS），赢家负责用原 epoch 重新 XADD。 */
    public Optional<AiJobRecord> casTouchQueued(AiJobRecord current, OffsetDateTime now) {
        return jdbc.query("""
                UPDATE ai_job SET version=version+1, updated_at=?
                 WHERE id=? AND version=? AND status='QUEUED' AND lease_epoch=?
                 RETURNING *
                """, jobMapper(), now, current.id(), current.version(), current.leaseEpoch())
                .stream().findFirst();
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

    /** 规则 1：{@code updated_at} 早于 {@code before} 仍未被认领的 QUEUED 任务（未过 deadline）。 */
    public List<AiJobRecord> findStaleQueued(OffsetDateTime before, OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_job
                 WHERE status='QUEUED' AND updated_at < ?
                   AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                   AND (deadline_at IS NULL OR deadline_at>?)
                 ORDER BY updated_at, id LIMIT ?
                """, jobMapper(), before, now, now, Math.max(1, Math.min(limit, 200)));
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
                rs.getInt("version"),
                optionalUuid(rs, "document_id"),
                optionalUuid(rs, "generation_id"),
                optionalUuid(rs, "parent_job_id"),
                optionalString(rs, "index_phase"));
    }

    /** V105 之前的库（部分集成测试只迁到 V104）没有这几列，读不到时取 {@code null}。 */
    private static UUID optionalUuid(ResultSet rs, String column) throws SQLException {
        int index = findColumn(rs, column);
        return index > 0 ? rs.getObject(index, UUID.class) : null;
    }

    private static String optionalString(ResultSet rs, String column) throws SQLException {
        int index = findColumn(rs, column);
        return index > 0 ? rs.getString(index) : null;
    }

    private static int findColumn(ResultSet rs, String column) throws SQLException {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(meta.getColumnLabel(i))) {
                return i;
            }
        }
        return -1;
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
