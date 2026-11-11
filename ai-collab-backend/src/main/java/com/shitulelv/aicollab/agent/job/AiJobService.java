package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * AI Job 服务（W5 冻结 §3.2）：租约/心跳/结果幂等/重试/过期。
 *
 * <p><b>红线</b>：状态机、租约、重试、结果落库全部在 Java；Python 不调 度、不决定重试、
 * 不发布候选。本服务没有任何 {@code agent_memory} 写入路径，也没有 ACCEPTED/REJECTED 的
 * 结果审核路径。</p>
 *
 * <p><b>并发</b>：所有跃迁先 CAS {@code version}，失败重读一次，二次失败抛
 * {@link AiJobException.Kind#STALE_LEASE}。心跳/结果还必须匹配 {@code leaseEpoch}。</p>
 *
 * <p><b>快照</b>：只读项目名/描述与活跃 {@code agent_memory}；facts ≤32×500、
 * existingMemories ≤50×{id,type,title,content≤500}，响应 <64 KiB，哈希覆盖除
 * {@code snapshotHash} 外的全部字段。</p>
 */
@Service
public class AiJobService {

    static final String METRIC_TRANSITIONS = "ai_job.transitions";
    static final String METRIC_SUCCESS_TOTAL = "ai_job.success_total";
    static final String METRIC_FAILURE_TOTAL = "ai_job.failure_total";
    static final String METRIC_LEASE_EXPIRED_TOTAL = "ai_job.lease_expired_total";
    static final String METRIC_RESULT_DUPLICATE_TOTAL = "ai_job.result_duplicate_total";
    static final String METRIC_RESULTS_TOTAL = "ai_job.results_total";

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
    private final Clock clock;

    @Autowired
    public AiJobService(AiJobRepository repository, AiJobProperties properties,
            MeterRegistry meterRegistry, ObjectMapper json) {
        this(repository, properties, meterRegistry, json, Clock.systemUTC());
    }

    /** 测试可注入固定时钟。 */
    AiJobService(AiJobRepository repository, AiJobProperties properties,
            MeterRegistry meterRegistry, ObjectMapper json, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------
    // 创建 / 租约 / 心跳
    // ------------------------------------------------------------------

    public AiJobRecord create(UUID projectId, String payloadJson, UUID createdBy) {
        return repository.create(projectId, "MEMORY_EXTRACTION", payloadJson,
                properties.maxAttempts(), null, createdBy);
    }

    @Transactional
    public Lease lease(UUID jobId) {
        AiJobRecord current = repository.require(jobId);
        if (current.status() != AiJobStatus.QUEUED) {
            throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                    "AI Job 不是 QUEUED，不能租约");
        }
        OffsetDateTime now = now();
        if (current.deadlineAt() != null && !now.isBefore(current.deadlineAt())) {
            expireDeadline(current, now);
            throw new AiJobException(AiJobException.Kind.INVALID_STATE, "AI Job 已超过 deadline");
        }
        OffsetDateTime leaseExpiresAt = now.plusSeconds(properties.leaseSeconds());
        UUID leaseToken = UUID.randomUUID();
        Optional<AiJobRecord> leased = repository.casLease(current, leaseToken, now, leaseExpiresAt);
        if (leased.isEmpty()) {
            AiJobRecord reread = repository.require(jobId);
            if (reread.status() != AiJobStatus.QUEUED) {
                throw new AiJobException(AiJobException.Kind.INVALID_STATE,
                        "AI Job 租约竞争失败");
            }
            leased = repository.casLease(reread, leaseToken, now, leaseExpiresAt);
            if (leased.isEmpty()) {
                throw new AiJobException(AiJobException.Kind.STALE_LEASE,
                        "AI Job 租约竞争失败");
            }
        }
        AiJobRecord job = leased.get();
        repository.insertAttempt(job.id(), job.attemptCount(), job.leaseEpoch(), now);
        recordTransition(AiJobStatus.QUEUED, AiJobStatus.LEASED);
        return new Lease(job, job.attemptCount(), job.leaseEpoch(), job.leaseToken());
    }

    @Transactional
    public Heartbeat heartbeat(UUID jobId, int attempt, long leaseEpoch) {
        AiJobRecord current = requireForLease(jobId, attempt, leaseEpoch);
        if (!current.status().leased()) {
            throw staleLease();
        }
        OffsetDateTime now = now();
        if (current.leaseExpiresAt() != null && current.leaseExpiresAt().isBefore(now)) {
            expireLease(current, now);
            throw staleLease();
        }
        OffsetDateTime newExpiry = now.plusSeconds(properties.leaseSeconds());
        Optional<AiJobRecord> updated = repository.casHeartbeat(current, leaseEpoch, newExpiry, now);
        if (updated.isEmpty()) {
            AiJobRecord reread = repository.require(jobId);
            if (!reread.status().leased() || reread.leaseEpoch() != leaseEpoch
                    || reread.attemptCount() != attempt) {
                throw staleLease();
            }
            updated = repository.casHeartbeat(reread, leaseEpoch, newExpiry, now);
            if (updated.isEmpty()) {
                throw staleLease();
            }
            current = reread;
        }
        AiJobRecord job = updated.get();
        if (current.status() == AiJobStatus.LEASED && job.status() == AiJobStatus.RUNNING) {
            recordTransition(AiJobStatus.LEASED, AiJobStatus.RUNNING);
        }
        repository.touchAttemptHeartbeat(jobId, attempt, leaseEpoch, now);
        return new Heartbeat(job.id(), job.status(), job.leaseExpiresAt());
    }

    // ------------------------------------------------------------------
    // 结果 / 失败
    // ------------------------------------------------------------------

    @Transactional
    public ResultsOutcome submitResults(UUID jobId, int attempt, long leaseEpoch,
            List<AiJobCandidate> candidates) {
        AiJobRecord current = requireForLease(jobId, attempt, leaseEpoch);
        if (!current.status().leased() && current.status() != AiJobStatus.SUCCEEDED) {
            throw staleLease();
        }
        OffsetDateTime now = now();
        int accepted = 0;
        int duplicates = 0;
        for (AiJobCandidate candidate : candidates == null ? List.<AiJobCandidate>of() : candidates) {
            if (candidate == null || candidate.dedupeKey() == null || candidate.dedupeKey().isBlank()) {
                throw new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                        "结果候选缺少 dedupeKey");
            }
            AiJobRepository.ResultWrite write = repository.appendResult(jobId, attempt,
                    RESULT_KIND_MEMORY_CANDIDATE, candidate.dedupeKey(), writePayload(candidate), now);
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
        if (current.status() == AiJobStatus.SUCCEEDED) {
            return new ResultsOutcome(jobId, AiJobStatus.SUCCEEDED, accepted, duplicates);
        }
        AiJobRecord running = current;
        if (running.status() == AiJobStatus.LEASED) {
            running = transition(running, AiJobStatus.RUNNING, leaseEpoch, null, null, now);
        }
        AiJobRecord succeeded = transition(running, AiJobStatus.SUCCEEDED, leaseEpoch,
                null, null, now);
        repository.finishAttempt(jobId, attempt, AiJobAttemptStatus.SUCCEEDED, null, now);
        incrementCounter(METRIC_SUCCESS_TOTAL, 1);        return new ResultsOutcome(jobId, succeeded.status(), accepted, duplicates);
    }

    @Transactional
    public AiJobRecord fail(UUID jobId, int attempt, long leaseEpoch, boolean retryable,
            String errorCode) {
        AiJobRecord current = requireForLease(jobId, attempt, leaseEpoch);
        if (!current.status().leased()) {
            throw staleLease();
        }
        OffsetDateTime now = now();
        if (current.status() == AiJobStatus.LEASED) {
            current = transition(current, AiJobStatus.RUNNING, leaseEpoch, null, null, now);
        }
        boolean canRetry = retryable && current.attemptCount() < current.maxAttempts();
        AiJobStatus target = canRetry ? AiJobStatus.FAILED_RETRYABLE : AiJobStatus.FAILED;
        OffsetDateTime nextAttemptAt = canRetry
                ? now.plusSeconds(backoffSeconds(current.attemptCount())) : null;
        AiJobRecord failed = transition(current, target, leaseEpoch, errorCode, nextAttemptAt, now);
        repository.finishAttempt(jobId, attempt,
                canRetry ? AiJobAttemptStatus.FAILED_RETRYABLE : AiJobAttemptStatus.FAILED,
                errorCode, now);
        incrementCounter(METRIC_FAILURE_TOTAL, 1, "retryable", canRetry ? "true" : "false");
        return failed;
    }

    // ------------------------------------------------------------------
    // 过期 / 恢复
    // ------------------------------------------------------------------

    /** 租约过期回收：attempt<max → FAILED_RETRYABLE，否则 FAILED；不依赖 Python。 */
    @Transactional
    public RecoverySummary recoverExpired(OffsetDateTime now, int limit) {
        int leaseExpired = 0;
        int deadlineExpired = 0;
        int requeued = 0;
        for (AiJobRecord job : repository.findLeaseExpired(now, limit)) {
            try {
                expireLease(job, now);
                leaseExpired++;
            } catch (RuntimeException ignored) {
                // 已被其它实例回收/推进：跳过，下一轮扫描对账。
            }
        }
        for (AiJobRecord job : repository.findDeadlineExceeded(now, limit)) {
            try {
                expireDeadline(job, now);
                deadlineExpired++;
            } catch (RuntimeException ignored) {
                // 同上。
            }
        }
        for (AiJobRecord job : repository.findRetryDue(now, limit)) {
            try {
                transition(job, AiJobStatus.QUEUED, job.leaseEpoch(), null, null, now);
                requeued++;
            } catch (RuntimeException ignored) {
                // 同上。
            }
        }
        return new RecoverySummary(leaseExpired, deadlineExpired, requeued);
    }

    private void expireLease(AiJobRecord current, OffsetDateTime now) {
        boolean canRetry = current.attemptCount() < current.maxAttempts();
        AiJobStatus target = canRetry ? AiJobStatus.FAILED_RETRYABLE : AiJobStatus.FAILED;
        OffsetDateTime nextAttemptAt = canRetry
                ? now.plusSeconds(backoffSeconds(current.attemptCount())) : null;
        AiJobRecord expired = transition(current, target, current.leaseEpoch(),
                "LEASE_EXPIRED", nextAttemptAt, now);
        repository.finishAttempt(current.id(), expired.attemptCount(),
                canRetry ? AiJobAttemptStatus.FAILED_RETRYABLE : AiJobAttemptStatus.FAILED,
                "LEASE_EXPIRED", now);
        incrementCounter(METRIC_LEASE_EXPIRED_TOTAL, 1);
    }

    private void expireDeadline(AiJobRecord current, OffsetDateTime now) {
        AiJobRecord expired = transition(current, AiJobStatus.EXPIRED, current.leaseEpoch(),
                "DEADLINE_EXCEEDED", null, now);
        if (current.status().leased()) {
            repository.finishAttempt(current.id(), expired.attemptCount(),
                    AiJobAttemptStatus.EXPIRED, "DEADLINE_EXCEEDED", now);
        }
    }

    // ------------------------------------------------------------------
    // 有界快照
    // ------------------------------------------------------------------

    public Snapshot snapshot(UUID jobId, int attempt, long leaseEpoch) {
        AiJobRecord current = requireForLease(jobId, attempt, leaseEpoch);
        if (!current.status().leased()) {
            throw staleLease();
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
        String hash = snapshotHash(current, boundedFacts, boundedMemories);
        return new Snapshot(current.id(), current.projectId(), attempt, leaseEpoch, hash,
                boundedFacts, boundedMemories);
    }

    private int snapshotBytes(AiJobRecord job, List<Fact> facts, List<ExistingMemory> memories) {
        Map<String, Object> payload = snapshotPayload(job, facts, memories);
        try {
            return json.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("AI Job 快照序列化失败", impossible);
        }
    }

    private String snapshotHash(AiJobRecord job, List<Fact> facts, List<ExistingMemory> memories) {
        try {
            byte[] canonical = json.writeValueAsBytes(snapshotPayload(job, facts, memories));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return SNAPSHOT_HASH_PREFIX
                    + HexFormat.of().formatHex(digest.digest(canonical)).substring(0, 32);
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("AI Job 快照哈希计算失败", failure);
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

    private AiJobRecord requireForLease(UUID jobId, int attempt, long leaseEpoch) {
        AiJobRecord current = repository.require(jobId);
        if (current.attemptCount() != attempt || current.leaseEpoch() != leaseEpoch) {
            throw staleLease();
        }
        return current;
    }

    /**
     * 执行一次合法跃迁：CAS 失败重读一次；二次失败或重读后跃迁非法 → 409 STALE_LEASE。
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
        return new AiJobException(AiJobException.Kind.STALE_LEASE, "AI Job 租约已失效");
    }

    private String writePayload(AiJobCandidate candidate) {
        try {
            return json.writeValueAsString(candidate);
        } catch (JsonProcessingException failure) {
            throw new AiJobException(AiJobException.Kind.INVALID_REQUEST,
                    "结果候选序列化失败");
        }
    }

    /** 指数退避：30/120/480/…，上限 600 秒（与冻结 §3.2 一致）。 */
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

    public record Heartbeat(UUID jobId, AiJobStatus status, OffsetDateTime leaseExpiresAt) {
    }

    public record ResultsOutcome(UUID jobId, AiJobStatus status, int accepted, int duplicates) {
    }

    public record RecoverySummary(int leaseExpired, int deadlineExpired, int requeued) {
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
}
