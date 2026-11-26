package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AI 任务服务（P1 契约 §4）：创建 → Stream 派发 → claim/heartbeat/result/fail → 补偿扫描。
 *
 * <p><b>红线</b>：状态机、租约、重试、结果落库全部在 Java；Python 只经 {@code /internal/ai/tasks/**}
 * 回调。本服务没有任何 {@code agent_memory} 写入路径，候选只进 {@code ai_job_result}（PENDING_REVIEW）。</p>
 *
 * <p><b>epoch 语义</b>（契约 §4.3/§4.4）：claim 是 {@code status='QUEUED' AND lease_epoch=:epoch} 的条件更新，
 * <b>不改变</b> epoch；只有补偿扫描收回任务（租约过期 / 到期重试 / 过期 / 耗尽）时 {@code lease_epoch+1}。
 * heartbeat/result/fail 必须同时匹配 {@code epoch} 与 {@code leaseToken}，否则 {@link AiJobException.Kind#STALE_LEASE}
 * （控制器映射 409 {@code TASK_EPOCH_STALE}）。</p>
 *
 * <p><b>幂等</b>：同 epoch + 同 leaseToken 对已离开租约的任务（SUCCEEDED/FAILED/FAILED_RETRYABLE/CANCELED/EXPIRED）
 * 重复 result/fail，返回当前状态且不写任何行。</p>
 *
 * <p><b>快照</b>：只读项目名/描述与活跃 {@code agent_memory}；facts ≤32×500、
 * existingMemories ≤50×{id,type,title,content≤500}，响应 &lt;64 KiB，哈希覆盖除
 * {@code snapshotHash} 外的全部字段。首次读取时冻结到 {@code ai_job_attempt}。</p>
 */
@Service
public class AiJobService {

    public static final String TASK_TYPE_MEMORY_EXTRACTION = "MEMORY_EXTRACTION";

    static final String METRIC_TRANSITIONS = "ai_job.transitions";
    static final String METRIC_SUCCESS_TOTAL = "ai_job.success_total";
    static final String METRIC_FAILURE_TOTAL = "ai_job.failure_total";
    static final String METRIC_LEASE_EXPIRED_TOTAL = "ai_job.lease_expired_total";
    static final String METRIC_RESULT_DUPLICATE_TOTAL = "ai_job.result_duplicate_total";
    static final String METRIC_RESULTS_TOTAL = "ai_job.results_total";

    static final long QUEUED_REDISPATCH_AFTER_SECONDS = 60L;
    private static final int MAX_ERROR_CODE_CHARS = 64;
    private static final String RESULT_KIND_MEMORY_CANDIDATE = "MEMORY_CANDIDATE";
    private static final String SNAPSHOT_HASH_PREFIX = "sha256:";
    private static final int MAX_SNAPSHOT_FACTS = 32;
    private static final int MAX_SNAPSHOT_MEMORIES = 50;
    private static final int MAX_FACT_CHARS = 500;
    private static final int MAX_MEMORY_CONTENT_CHARS = 500;
    private static final int MAX_MEMORY_TITLE_CHARS = 200;
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    private static final long RETRY_BASE_SECONDS = 30L;
    private static final long RETRY_MAX_SECONDS = 600L;

    private final AiJobRepository repository;
    private final AiJobProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public AiJobService(AiJobRepository repository, AiJobProperties properties,
            MeterRegistry meterRegistry, ObjectMapper json, ApplicationEventPublisher events) {
        this(repository, properties, meterRegistry, json, events, Clock.systemUTC());
    }

    /** 测试可注入固定时钟。 */
    public AiJobService(AiJobRepository repository, AiJobProperties properties,
            MeterRegistry meterRegistry, ObjectMapper json, ApplicationEventPublisher events,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------
    // 创建 / 认领 / 心跳
    // ------------------------------------------------------------------

    /** 业务事务内写 {@code ai_job(status=QUEUED)}；AFTER_COMMIT 由监听方 XADD（契约 §4.2）。 */
    @Transactional
    public AiJobRecord create(UUID projectId, String payloadJson, UUID createdBy) {
        AiJobRecord job = repository.create(projectId, TASK_TYPE_MEMORY_EXTRACTION, payloadJson,
                properties.maxAttempts(), null, createdBy);
        events.publishEvent(new AiJobQueuedEvent(job));
        return job;
    }

    /**
     * claim（契约 §4.3）：条件更新 {@code QUEUED AND lease_epoch=:epoch} → LEASED，新租约 60s。
     * 任何不满足（已被认领、epoch 过期、状态不对、类型不符、任务不存在）都抛
     * {@link AiJobException.Kind#CLAIM_CONFLICT}。
     */
    @Transactional
    public Lease claim(UUID taskId, String taskType, long epoch) {
        Optional<AiJobRecord> existing = repository.find(taskId);
        if (existing.isEmpty() || !existing.get().jobType().equals(taskType)) {
            throw claimConflict();
        }
        OffsetDateTime now = now();
        UUID leaseToken = UUID.randomUUID();
        AiJobRecord job = repository.casClaim(taskId, epoch, leaseToken, now,
                        now.plusSeconds(properties.leaseSeconds()))
                .orElseThrow(AiJobService::claimConflict);
        repository.insertAttempt(job.id(), job.attemptCount(), job.leaseEpoch(), now);
        recordTransition(AiJobStatus.QUEUED, AiJobStatus.LEASED);
        return new Lease(job, job.attemptCount(), job.leaseEpoch(), job.leaseToken());
    }

    /**
     * heartbeat（契约 §4.3）：续租 60s；首次心跳 LEASED → RUNNING。
     * 任务已被标记 CANCELED 且租约仍属于调用方时返回 {@code cancelRequested=true}。
     */
    @Transactional
    public Heartbeat heartbeat(UUID taskId, long epoch, UUID leaseToken) {
        AiJobRecord current = requireOwnedLease(taskId, epoch, leaseToken);
        if (current.status() == AiJobStatus.CANCELED) {
            return new Heartbeat(current, true);
        }
        if (!current.status().leased()) {
            throw staleLease();
        }
        OffsetDateTime now = now();
        AiJobRecord job = repository.casHeartbeat(taskId, epoch, leaseToken,
                        now.plusSeconds(properties.leaseSeconds()), now)
                .orElseThrow(AiJobService::staleLease);
        if (current.status() == AiJobStatus.LEASED && job.status() == AiJobStatus.RUNNING) {
            recordTransition(AiJobStatus.LEASED, AiJobStatus.RUNNING);
        }
        repository.touchAttemptHeartbeat(taskId, job.attemptCount(), epoch, now);
        return new Heartbeat(job, false);
    }

    // ------------------------------------------------------------------
    // 结果 / 失败
    // ------------------------------------------------------------------

    /**
     * result（契约 §4.3）：沿用快照绑定与候选落库校验，置 SUCCEEDED。
     * 同 epoch + 同 leaseToken 对已离开租约的任务重复提交：返回当前状态、不写任何行。
     */
    @Transactional
    public ResultsOutcome submitResult(UUID taskId, long epoch, UUID leaseToken,
            List<AiJobCandidate> candidates) {
        AiJobRecord current = requireOwnedLease(taskId, epoch, leaseToken);
        if (finished(current)) {
            return new ResultsOutcome(taskId, current.status(), 0, 0, true);
        }
        int attempt = current.attemptCount();
        AiJobRepository.AttemptSnapshot snapshot = requireAttemptSnapshot(taskId, attempt, epoch);
        storedSnapshot(current, snapshot);
        Set<String> snapshotRefs = snapshotRefs(snapshot.snapshotRefsJson());
        List<AiJobCandidate> submitted = candidates == null ? List.of() : candidates;
        for (AiJobCandidate candidate : submitted) {
            requireSnapshotSourceRef(candidate, snapshotRefs);
        }
        OffsetDateTime now = now();
        int accepted = 0;
        int duplicates = 0;
        for (AiJobCandidate candidate : submitted) {
            AiJobRepository.ResultWrite write = repository.appendResult(taskId, attempt,
                    RESULT_KIND_MEMORY_CANDIDATE, candidate.dedupeKey(), writePayload(candidate),
                    snapshot.snapshotHash(), snapshot.snapshotRefsJson(), now);
            if (write.inserted()) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        if (accepted > 0) {
            incrementCounter(METRIC_RESULTS_TOTAL, accepted);
        }
        if (duplicates > 0) {
            incrementCounter(METRIC_RESULT_DUPLICATE_TOTAL, duplicates, "result", "duplicate");
        }
        AiJobRecord running = current;
        if (running.status() == AiJobStatus.LEASED) {
            running = transition(running, AiJobStatus.RUNNING, epoch, null, null, now);
        }
        AiJobRecord succeeded = transition(running, AiJobStatus.SUCCEEDED, epoch, null, null, now);
        repository.finishAttempt(taskId, attempt, AiJobAttemptStatus.SUCCEEDED, null, now);
        incrementCounter(METRIC_SUCCESS_TOTAL, 1);
        return new ResultsOutcome(taskId, succeeded.status(), accepted, duplicates, false);
    }

    /**
     * fail（契约 §4.3）：retryable 且仍有剩余次数 → FAILED_RETRYABLE（补偿扫描到期后重排），否则 FAILED。
     * 同 epoch + 同 leaseToken 重复提交：返回当前状态、不写任何行。
     */
    @Transactional
    public AiJobRecord fail(UUID taskId, long epoch, UUID leaseToken, boolean retryable,
            String errorCode) {
        AiJobRecord current = requireOwnedLease(taskId, epoch, leaseToken);
        if (finished(current)) {
            return current;
        }
        OffsetDateTime now = now();
        String code = bound(errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode,
                MAX_ERROR_CODE_CHARS);
        if (current.status() == AiJobStatus.LEASED) {
            current = transition(current, AiJobStatus.RUNNING, epoch, null, null, now);
        }
        boolean canRetry = retryable && current.attemptCount() < current.maxAttempts();
        AiJobStatus target = canRetry ? AiJobStatus.FAILED_RETRYABLE : AiJobStatus.FAILED;
        OffsetDateTime nextAttemptAt = canRetry
                ? now.plusSeconds(backoffSeconds(current.attemptCount())) : null;
        AiJobRecord failed = transition(current, target, epoch, code, nextAttemptAt, now);
        repository.finishAttempt(taskId, current.attemptCount(),
                canRetry ? AiJobAttemptStatus.FAILED_RETRYABLE : AiJobAttemptStatus.FAILED,
                code, now);
        incrementCounter(METRIC_FAILURE_TOTAL, 1, "retryable", canRetry ? "true" : "false");
        return failed;
    }

    // ------------------------------------------------------------------
    // 补偿扫描（契约 §4.4）
    // ------------------------------------------------------------------

    /**
     * 一轮补偿扫描。每条推进都是单条 CAS（version + status），两个扫描器并发时同一任务只有一个能推进；
     * 只有 CAS 赢家才发布 {@link AiJobQueuedEvent}（重新 XADD）。不加方法级事务：每条推进独立提交，
     * XADD 在提交后立即执行。
     *
     * <ol>
     *   <li>超过 deadline → EXPIRED（epoch+1）；</li>
     *   <li>LEASED/RUNNING 且租约过期 → epoch+1 回到 QUEUED 并重新 XADD；attempt 已耗尽则 FAILED；</li>
     *   <li>FAILED_RETRYABLE 且到期 → 同第 2 条；</li>
     *   <li>QUEUED 且 {@code updated_at} 早于 60s → 用原 epoch 重新 XADD。</li>
     * </ol>
     */
    public CompensationSummary compensate(OffsetDateTime now, int limit) {
        int expired = 0;
        int requeued = 0;
        int exhausted = 0;
        int redispatched = 0;
        for (AiJobRecord job : repository.findDeadlineExceeded(now, limit)) {
            if (fence(job, AiJobStatus.EXPIRED, "DEADLINE_EXCEEDED", now).isPresent()) {
                expired++;
            }
        }
        List<AiJobRecord> reclaimable = new ArrayList<>(repository.findLeaseExpired(now, limit));
        reclaimable.addAll(repository.findRetryDue(now, limit));
        for (AiJobRecord job : reclaimable) {
            boolean leaseExpired = job.status().leased();
            boolean exhaustedAttempts = job.attemptCount() >= job.maxAttempts();
            AiJobStatus target = exhaustedAttempts ? AiJobStatus.FAILED : AiJobStatus.QUEUED;
            String code = leaseExpired ? "LEASE_EXPIRED" : null;
            Optional<AiJobRecord> moved = fence(job, target, code, now);
            if (moved.isEmpty()) {
                continue;
            }
            if (leaseExpired) {
                incrementCounter(METRIC_LEASE_EXPIRED_TOTAL, 1);
            }
            if (exhaustedAttempts) {
                exhausted++;
            } else {
                requeued++;
                events.publishEvent(new AiJobQueuedEvent(moved.get()));
            }
        }
        OffsetDateTime staleBefore = now.minusSeconds(QUEUED_REDISPATCH_AFTER_SECONDS);
        for (AiJobRecord job : repository.findStaleQueued(staleBefore, now, limit)) {
            Optional<AiJobRecord> touched = repository.casTouchQueued(job, now);
            if (touched.isPresent()) {
                redispatched++;
                events.publishEvent(new AiJobQueuedEvent(touched.get()));
            }
        }
        return new CompensationSummary(redispatched, requeued, exhausted, expired);
    }

    /** 围栏跃迁：epoch+1、清空租约；离开租约的 attempt 同步收尾。CAS 失败（已被他人推进）返回空。 */
    private Optional<AiJobRecord> fence(AiJobRecord current, AiJobStatus target, String errorCode,
            OffsetDateTime now) {
        if (!AiJobStateMachine.canTransition(current.status(), target)) {
            return Optional.empty();
        }
        Optional<AiJobRecord> moved = repository.casFence(current, target, errorCode, now);
        if (moved.isEmpty()) {
            return moved;
        }
        recordTransition(current.status(), target);
        if (current.status().leased() && current.attemptCount() > 0) {
            AiJobAttemptStatus attemptStatus = switch (target) {
                case EXPIRED -> AiJobAttemptStatus.EXPIRED;
                case FAILED -> AiJobAttemptStatus.FAILED;
                default -> AiJobAttemptStatus.FAILED_RETRYABLE;
            };
            repository.finishAttempt(current.id(), current.attemptCount(), attemptStatus,
                    errorCode, now);
        }
        return moved;
    }

    // ------------------------------------------------------------------
    // 有界快照
    // ------------------------------------------------------------------

    /** {@code GET /internal/ai/tasks/{taskId}/snapshot}：令牌 {@code ep} 必须等于当前租约 epoch。 */
    public Snapshot snapshotForTask(UUID taskId, long epoch) {
        AiJobRecord current = repository.find(taskId).orElseThrow(AiJobService::staleLease);
        if (!current.status().leased() || current.leaseEpoch() != epoch) {
            throw staleLease();
        }
        return snapshot(taskId, current.attemptCount(), epoch);
    }

    public Snapshot snapshot(UUID jobId, int attempt, long leaseEpoch) {
        AiJobRecord current = repository.require(jobId);
        if (current.attemptCount() != attempt || current.leaseEpoch() != leaseEpoch
                || !current.status().leased()) {
            throw staleLease();
        }
        Optional<AiJobRepository.AttemptSnapshot> frozen =
                repository.findAttemptSnapshot(jobId, attempt, leaseEpoch);
        if (frozen.isPresent()) {
            return storedSnapshot(current, frozen.get());
        }
        List<Fact> facts = new ArrayList<>();
        repository.projectFacts(current.projectId()).ifPresent(project -> facts.add(
                new Fact("fact:project", "PROJECT",
                        bound(project.name() + "：" + project.description(), MAX_FACT_CHARS))));

        List<ExistingMemory> memories = new ArrayList<>();
        for (AiJobRepository.MemoryFact memory
                : repository.activeMemories(current.projectId(), MAX_SNAPSHOT_MEMORIES)) {
            if (memory == null || memory.id() == null) {
                continue;
            }
            memories.add(new ExistingMemory(memory.id(), bound(memory.type(), 24),
                    bound(memory.title(), MAX_MEMORY_TITLE_CHARS),
                    bound(memory.content(), MAX_MEMORY_CONTENT_CHARS)));
        }
        for (ExistingMemory memory : memories) {
            if (facts.size() >= MAX_SNAPSHOT_FACTS) {
                break;
            }
            facts.add(new Fact("memory:" + memory.id(), "MEMORY",
                    bound(memory.title() + "：" + memory.content(), MAX_FACT_CHARS)));
        }

        // 总量 <64 KiB：先裁记忆（同时移除对应 fact），再裁事实。
        while (snapshotBytes(current, facts, memories) > MAX_SNAPSHOT_BYTES && !memories.isEmpty()) {
            ExistingMemory removed = memories.removeLast();
            facts.removeIf(fact -> fact.ref().equals("memory:" + removed.id()));
        }
        while (snapshotBytes(current, facts, memories) > MAX_SNAPSHOT_BYTES && !facts.isEmpty()) {
            facts.removeLast();
        }
        List<Fact> boundedFacts = List.copyOf(facts);
        List<ExistingMemory> boundedMemories = List.copyOf(memories);
        String payloadJson = snapshotPayloadJson(current, boundedFacts, boundedMemories);
        String hash = snapshotHash(payloadJson);
        String refsJson = snapshotRefsJson(boundedFacts, boundedMemories);
        AiJobRepository.AttemptSnapshot persisted = repository.saveAttemptSnapshotIfAbsent(
                        jobId, attempt, leaseEpoch, hash, refsJson, payloadJson)
                .orElseThrow(() -> new AiJobException(AiJobException.Kind.INVALID_STATE,
                        "Job attempt 不存在，无法冻结快照"));
        return storedSnapshot(current, persisted);
    }

    private int snapshotBytes(AiJobRecord job, List<Fact> facts, List<ExistingMemory> memories) {
        Map<String, Object> payload = snapshotPayload(job, facts, memories);
        try {
            return json.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("AI Job 快照序列化失败", impossible);
        }
    }

    private String snapshotPayloadJson(AiJobRecord job, List<Fact> facts,
            List<ExistingMemory> memories) {
        try {
            return json.writeValueAsString(snapshotPayload(job, facts, memories));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("AI Job 快照序列化失败", failure);
        }
    }

    private static String snapshotHash(String payloadJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return SNAPSHOT_HASH_PREFIX + HexFormat.of()
                    .formatHex(digest.digest(payloadJson.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("AI Job 快照哈希计算失败", failure);
        }
    }

    private String snapshotRefsJson(List<Fact> facts, List<ExistingMemory> memories) {
        try {
            return json.writeValueAsString(snapshotRefs(facts, memories));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("AI Job 快照引用序列化失败", failure);
        }
    }

    private static Set<String> snapshotRefs(List<Fact> facts, List<ExistingMemory> memories) {
        Set<String> refs = new LinkedHashSet<>();
        for (Fact fact : facts) {
            refs.add(fact.ref());
        }
        for (ExistingMemory memory : memories) {
            refs.add("memory:" + memory.id());
        }
        return refs;
    }

    private Snapshot storedSnapshot(AiJobRecord current,
            AiJobRepository.AttemptSnapshot stored) {
        try {
            StoredSnapshotPayload payload = json.readValue(
                    stored.payloadJson(), StoredSnapshotPayload.class);
            if (!current.id().equals(stored.jobId())
                    || !current.projectId().equals(stored.projectId())
                    || payload.jobId() == null || !payload.jobId().equals(current.id())
                    || payload.projectId() == null
                    || !payload.projectId().equals(current.projectId())
                    || payload.attempt() != stored.attemptNo()
                    || payload.leaseEpoch() != stored.leaseEpoch()
                    || !snapshotHash(stored.payloadJson()).equals(stored.snapshotHash())
                    || !snapshotRefs(payload.facts(), payload.existingMemories())
                    .equals(snapshotRefs(stored.snapshotRefsJson()))) {
                throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                        "Job 快照绑定已变化");
            }
            return new Snapshot(stored.jobId(), stored.projectId(), stored.attemptNo(),
                    stored.leaseEpoch(), stored.snapshotHash(),
                    payload.facts(), payload.existingMemories());
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                    "Job 快照绑定已变化");
        }
    }

    private AiJobRepository.AttemptSnapshot requireAttemptSnapshot(UUID jobId, int attempt,
            long leaseEpoch) {
        return repository.findAttemptSnapshot(jobId, attempt, leaseEpoch)
                .orElseThrow(() -> new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                        "候选提交前必须先读取 Job 快照"));
    }

    private Set<String> snapshotRefs(String refsJson) {
        try {
            List<String> refs = json.readValue(
                    refsJson, new TypeReference<List<String>>() { });
            if (refs == null || refs.isEmpty()) {
                throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                        "Job 快照引用为空");
            }
            Set<String> unique = new LinkedHashSet<>(refs);
            if (unique.size() != refs.size()) {
                throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                        "Job 快照引用重复");
            }
            return unique;
        } catch (JsonProcessingException failure) {
            throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                    "Job 快照引用不合法");
        }
    }

    private static void requireSnapshotSourceRef(AiJobCandidate candidate,
            Set<String> snapshotRefs) {
        if (candidate == null || candidate.dedupeKey() == null
                || candidate.dedupeKey().isBlank()) {
            throw new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                    "结果候选缺少 dedupeKey");
        }
        String sourceRef = candidate.sourceRef();
        if (sourceRef == null || sourceRef.isBlank() || sourceRef.length() > 200
                || !(sourceRef.startsWith("fact:") || sourceRef.startsWith("memory:"))
                || !snapshotRefs.contains(sourceRef)) {
            throw new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                    "candidate_source_ref_invalid");
        }
    }

    private static Map<String, Object> snapshotPayload(AiJobRecord job, List<Fact> facts,
            List<ExistingMemory> memories) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.id());
        payload.put("projectId", job.projectId());
        payload.put("attempt", job.attemptCount());
        payload.put("leaseEpoch", job.leaseEpoch());
        payload.put("facts", facts);
        payload.put("existingMemories", memories);
        return payload;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** epoch 与 leaseToken 都必须属于调用方；任务不存在同样视为租约失效（409）。 */
    private AiJobRecord requireOwnedLease(UUID taskId, long epoch, UUID leaseToken) {
        AiJobRecord current = repository.find(taskId).orElseThrow(AiJobService::staleLease);
        if (current.leaseEpoch() != epoch || leaseToken == null
                || !leaseToken.equals(current.leaseToken())) {
            throw staleLease();
        }
        return current;
    }

    /** 本 epoch 的租约已经结束（成功/失败/取消/过期）；QUEUED 不可能出现在同一 epoch + leaseToken 下。 */
    private static boolean finished(AiJobRecord job) {
        return !job.status().leased() && job.status() != AiJobStatus.QUEUED;
    }

    /**
     * 执行一次合法跃迁：CAS 失败重读一次；二次失败或重读后跃迁非法 → STALE_LEASE。
     */
    private AiJobRecord transition(AiJobRecord current, AiJobStatus target, long leaseEpoch,
            String errorCode, OffsetDateTime nextAttemptAt, OffsetDateTime now) {
        requireTransition(current.status(), target);
        Optional<AiJobRecord> updated = repository.casTransition(current, target, leaseEpoch,
                errorCode, nextAttemptAt, now);
        if (updated.isEmpty()) {
            AiJobRecord reread = repository.require(current.id());
            requireTransition(reread.status(), target);
            updated = repository.casTransition(reread, target, leaseEpoch, errorCode,
                    nextAttemptAt, now);
            if (updated.isEmpty()) {
                throw staleLease();
            }
            current = reread;
        }
        recordTransition(current.status(), target);
        return updated.get();
    }

    private static void requireTransition(AiJobStatus from, AiJobStatus to) {
        try {
            AiJobStateMachine.requireTransition(from, to);
        } catch (IllegalArgumentException invalid) {
            throw staleLease();
        }
    }

    private static AiJobException staleLease() {
        return new AiJobException(AiJobException.Kind.STALE_LEASE, "AI 任务租约已失效");
    }

    private static AiJobException claimConflict() {
        return new AiJobException(AiJobException.Kind.CLAIM_CONFLICT, "AI 任务认领冲突");
    }

    private String writePayload(AiJobCandidate candidate) {
        try {
            return json.writeValueAsString(candidate);
        } catch (JsonProcessingException failure) {
            throw new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                    "结果候选序列化失败");
        }
    }

    /** 指数退避：30/120/480/…，上限 600 秒。 */
    static long backoffSeconds(int attempt) {
        int boundedAttempt = Math.max(1, attempt);
        long seconds = RETRY_BASE_SECONDS;
        for (int index = 1; index < boundedAttempt; index++) {
            seconds *= 4;
            if (seconds >= RETRY_MAX_SECONDS) {
                return RETRY_MAX_SECONDS;
            }
        }
        return Math.min(seconds, RETRY_MAX_SECONDS);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private void recordTransition(AiJobStatus from, AiJobStatus to) {
        try {
            meterRegistry.counter(METRIC_TRANSITIONS, "from", from.tag(), "to", to.tag()).increment();
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private void incrementCounter(String name, double amount, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment(amount);
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private static String bound(String value, int maxChars) {
        String text = value == null ? "" : value;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    // ------------------------------------------------------------------
    // 对外的值对象
    // ------------------------------------------------------------------

    public record Lease(AiJobRecord job, int attempt, long leaseEpoch, UUID leaseToken) {
    }

    public record Heartbeat(AiJobRecord job, boolean cancelRequested) {
    }

    /** {@code replayed=true}：同 epoch + 同 leaseToken 的重复提交，未写任何行。 */
    public record ResultsOutcome(UUID jobId, AiJobStatus status, int accepted, int duplicates,
            boolean replayed) {
    }

    public record CompensationSummary(int redispatched, int requeued, int exhausted, int expired) {
        public boolean idle() {
            return redispatched == 0 && requeued == 0 && exhausted == 0 && expired == 0;
        }
    }

    public record Snapshot(UUID jobId, UUID projectId, int attempt, long leaseEpoch,
            String snapshotHash, List<Fact> facts, List<ExistingMemory> existingMemories) {
        public Snapshot {
            facts = facts == null ? List.of() : List.copyOf(facts);
            existingMemories = existingMemories == null ? List.of() : List.copyOf(existingMemories);
        }
    }

    public record Fact(String ref, String kind, String text) {
    }

    public record ExistingMemory(UUID id, String type, String title, String content) {
    }

    private record StoredSnapshotPayload(UUID jobId, UUID projectId, int attempt, long leaseEpoch,
            List<Fact> facts, List<ExistingMemory> existingMemories) {
        private StoredSnapshotPayload {
            facts = facts == null ? List.of() : List.copyOf(facts);
            existingMemories = existingMemories == null ? List.of() : List.copyOf(existingMemories);
        }
    }
}
