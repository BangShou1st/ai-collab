package com.shitulelv.aicollab.internal.ai.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.job.AiJobCandidate;
import com.shitulelv.aicollab.agent.job.AiJobException;
import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.agent.job.AiJobTypes;
import com.shitulelv.aicollab.agent.job.AiTaskStreamPublisher;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P1 任务回调（契约 §4.3、R1 §9.3），替换 P1 之前的旧 Job 回调路径（已删除，不保留别名）。
 *
 * <p>鉴权由 {@code InternalAiTokenFilter} 完成：scope 必须含 {@code task:{taskId}}，且令牌 {@code tid}
 * 等于路径 taskId。本控制器再要求请求体 / 令牌里的 epoch 与任务当前 epoch 一致。</p>
 *
 * <p>状态码：claim 条件不满足 → 409 {@code TASK_CLAIM_CONFLICT}（R1：claim 只能 401/403/404/409）；
 * heartbeat/snapshot/result/fail 的 epoch 或 leaseToken 不符 → 409 {@code TASK_EPOCH_STALE}；
 * result 候选不合契约 → 422。claim/heartbeat 响应携带新令牌，固定 {@code Cache-Control: no-store}，
 * 日志不记任何令牌。</p>
 */
@RestController
@RequestMapping("/internal/ai/tasks/{taskId}")
public class InternalAiTaskController {

    private static final Logger log = LoggerFactory.getLogger(InternalAiTaskController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CANDIDATES = 50;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 2000;
    private static final int MAX_SOURCE_REF_CHARS = 200;
    private static final int MAX_WORKER_ID_CHARS = 200;
    private static final Set<String> CANDIDATE_KINDS =
            Set.of("DECISION", "PREFERENCE", "CONSTRAINT", "LESSON");
    private static final Pattern DEDUPE_KEY = Pattern.compile("^[0-9a-f]{64}$");

    private final AiJobService jobs;
    private final AiTaskStreamPublisher publisher;

    public InternalAiTaskController(AiJobService jobs, AiTaskStreamPublisher publisher) {
        this.jobs = jobs;
        this.publisher = publisher;
    }

    @PostMapping("/claim")
    public ResponseEntity<Object> claim(@PathVariable UUID taskId,
            @RequestBody(required = false) ClaimRequest body, HttpServletRequest request) {
        InternalAiClaims claims = InternalAiClaims.from(request);
        if (body == null || body.epoch() == null || body.taskType() == null
                || body.workerId() == null || body.workerId().isBlank()
                || body.workerId().length() > MAX_WORKER_ID_CHARS
                || !body.epoch().equals(claims.epoch())) {
            return error(InternalAiErrorCode.TASK_CLAIM_CONFLICT);
        }
        try {
            AiJobService.Lease lease = jobs.claim(taskId, body.taskType(), body.epoch());
            log.info("ai task claimed task_id={} epoch={} attempt={}", taskId, lease.leaseEpoch(),
                    lease.attempt());
            return noStore(new ClaimResponse(lease.leaseToken().toString(),
                    iso(lease.job().leaseExpiresAt()), publisher.taskToken(lease.job())));
        } catch (AiJobException failure) {
            return failure(taskId, "claim", failure);
        }
    }

    @GetMapping("/snapshot")
    public ResponseEntity<Object> snapshot(@PathVariable UUID taskId, HttpServletRequest request) {
        InternalAiClaims claims = InternalAiClaims.from(request);
        if (claims.epoch() == null) {
            return error(InternalAiErrorCode.TASK_EPOCH_STALE);
        }
        try {
            AiJobService.TaskSnapshot snap = jobs.taskSnapshot(taskId, claims.epoch());
            if (snap instanceof AiJobService.MemoryTaskSnapshot memory) {
                AiJobService.Snapshot s = memory.snapshot();
                return noStore(new SnapshotResponse(SCHEMA_VERSION, s.jobId(), s.projectId(),
                        s.attempt(), s.leaseEpoch(), s.snapshotHash(),
                        s.facts(), s.existingMemories()));
            }
            return noStore(snap);
        } catch (AiJobException failure) {
            return failure(taskId, "snapshot", failure);
        }
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Object> heartbeat(@PathVariable UUID taskId,
            @RequestBody(required = false) LeaseRequest body, HttpServletRequest request) {
        UUID leaseToken = leaseToken(body);
        if (body == null || body.epoch() == null || leaseToken == null
                || !body.epoch().equals(InternalAiClaims.from(request).epoch())) {
            return error(InternalAiErrorCode.TASK_EPOCH_STALE);
        }
        try {
            AiJobService.Heartbeat heartbeat = jobs.heartbeat(taskId, body.epoch(), leaseToken);
            AiJobRecord job = heartbeat.job();
            return noStore(new HeartbeatResponse(iso(job.leaseExpiresAt()),
                    heartbeat.cancelRequested(), publisher.taskToken(job)));
        } catch (AiJobException failure) {
            return failure(taskId, "heartbeat", failure);
        }
    }

    @PostMapping("/result")
    public ResponseEntity<Object> result(@PathVariable UUID taskId,
            @RequestBody(required = false) ResultRequest body) {
        UUID leaseToken = body == null ? null : parseUuid(body.leaseToken());
        if (body == null || body.epoch() == null || leaseToken == null) {
            return error(InternalAiErrorCode.TASK_EPOCH_STALE);
        }
        final AiJobRecord owned;
        try {
            owned = jobs.requireOwnedTaskForResult(taskId, body.epoch(), leaseToken);
        } catch (AiJobException failure) {
            return failure(taskId, "result", failure);
        }
        JsonNode payloadNode = body.payload() == null ? null : JSON.valueToTree(body.payload());
        if (AiJobTypes.MEMORY_EXTRACTION.equals(owned.jobType())) {
            return handleMemoryResult(taskId, body.epoch(), leaseToken, payloadNode);
        }
        try {
            AiJobService.ResultsOutcome outcome = jobs.submitIndexResult(
                    taskId, body.epoch(), leaseToken, payloadNode);
            log.info("ai index task result task_id={} type={} epoch={} status={} replayed={}",
                    taskId, owned.jobType(), body.epoch(), outcome.status(), outcome.replayed());
            return ResponseEntity.ok(new ResultResponse(outcome.status().name(), outcome.accepted(),
                    outcome.duplicates()));
        } catch (AiJobException failure) {
            return failure(taskId, "result", failure);
        }
    }

    private ResponseEntity<Object> handleMemoryResult(UUID taskId, long epoch, UUID leaseToken,
            JsonNode rawPayload) {
        ResultPayload parsed = null;
        if (rawPayload != null && !rawPayload.isNull()) {
            if (!rawPayload.isObject()) {
                return error(InternalAiErrorCode.INVALID_REQUEST, "payload 必须为 JSON 对象");
            }
            try {
                parsed = JSON.treeToValue(rawPayload, ResultPayload.class);
            } catch (JsonProcessingException invalid) {
                return error(InternalAiErrorCode.INVALID_REQUEST, "payload 结构不合法");
            }
        }
        List<Candidate> candidates = parsed == null || parsed.candidates() == null
                ? List.of() : parsed.candidates();
        if (parsed != null && parsed.schemaVersion() != null
                && parsed.schemaVersion() != SCHEMA_VERSION) {
            return error(InternalAiErrorCode.SCHEMA_VIOLATION, "payload.schemaVersion 必须为 1");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            return error(InternalAiErrorCode.INVALID_REQUEST, "candidates 不能超过 50 条");
        }
        List<AiJobCandidate> mapped = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            String violation = validate(candidate);
            if (violation != null) {
                return error(InternalAiErrorCode.INVALID_REQUEST, violation);
            }
            mapped.add(new AiJobCandidate(candidate.kind(), candidate.title(), candidate.content(),
                    candidate.confidence(), candidate.sourceRef(), candidate.dedupeKey()));
        }
        try {
            AiJobService.ResultsOutcome outcome = jobs.submitResult(taskId, epoch, leaseToken, mapped);
            log.info("ai task result task_id={} epoch={} status={} accepted={} duplicates={} replayed={}",
                    taskId, epoch, outcome.status(), outcome.accepted(), outcome.duplicates(),
                    outcome.replayed());
            return ResponseEntity.ok(new ResultResponse(outcome.status().name(), outcome.accepted(),
                    outcome.duplicates()));
        } catch (AiJobException failure) {
            return failure(taskId, "result", failure);
        }
    }

    @PostMapping("/fail")
    public ResponseEntity<Object> fail(@PathVariable UUID taskId,
            @RequestBody(required = false) FailRequest body) {
        UUID leaseToken = body == null ? null : parseUuid(body.leaseToken());
        if (body == null || body.epoch() == null || leaseToken == null) {
            return error(InternalAiErrorCode.TASK_EPOCH_STALE);
        }
        try {
            AiJobRecord job = jobs.fail(taskId, body.epoch(), leaseToken,
                    Boolean.TRUE.equals(body.retryable()), body.errorCode());
            log.info("ai task fail task_id={} epoch={} status={}", taskId, body.epoch(), job.status());
            return ResponseEntity.ok(new FailResponse(job.status().name()));
        } catch (AiJobException failure) {
            return failure(taskId, "fail", failure);
        }
    }

    /** 请求体不是合法 JSON：claim 只能回 409（R1 §9.3），其余按 422。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.endsWith("/claim")) {
            return error(InternalAiErrorCode.TASK_CLAIM_CONFLICT);
        }
        return error(InternalAiErrorCode.INVALID_REQUEST, "请求体不是合法 JSON");
    }

    // ------------------------------------------------------------------

    private static ResponseEntity<Object> failure(UUID taskId, String op, AiJobException failure) {
        InternalAiErrorCode code = switch (failure.kind()) {
            case CLAIM_CONFLICT -> InternalAiErrorCode.TASK_CLAIM_CONFLICT;
            case STALE_LEASE, INVALID_STATE, NOT_FOUND -> InternalAiErrorCode.TASK_EPOCH_STALE;
            case BODY_NOT_FOUND -> InternalAiErrorCode.NOT_FOUND;
            case INDEX_SNAPSHOT_STALE -> InternalAiErrorCode.INDEX_SNAPSHOT_STALE;
            case SCHEMA_VIOLATION -> InternalAiErrorCode.SCHEMA_VIOLATION;
            case INVALID_REQUEST -> InternalAiErrorCode.INVALID_REQUEST;
        };
        log.info("ai task {} rejected task_id={} kind={}", op, taskId, failure.kind());
        return (code == InternalAiErrorCode.INVALID_REQUEST || code == InternalAiErrorCode.SCHEMA_VIOLATION)
                ? error(code, failure.getMessage()) : error(code);
    }

    private static String validate(Candidate candidate) {
        if (candidate == null) {
            return "candidate 不能为空";
        }
        if (candidate.kind() == null || !CANDIDATE_KINDS.contains(candidate.kind())) {
            return "candidate.kind 必须是 DECISION/PREFERENCE/CONSTRAINT/LESSON";
        }
        if (candidate.title() == null || candidate.title().isBlank()
                || candidate.title().length() > MAX_TITLE_CHARS) {
            return "candidate.title 必须为 1–200 字符";
        }
        if (candidate.content() == null || candidate.content().isBlank()
                || candidate.content().length() > MAX_CONTENT_CHARS) {
            return "candidate.content 必须为 1–2000 字符";
        }
        BigDecimal confidence = candidate.confidence();
        if (confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            return "candidate.confidence 必须在 0..1";
        }
        if (candidate.sourceRef() == null || candidate.sourceRef().isBlank()
                || candidate.sourceRef().length() > MAX_SOURCE_REF_CHARS) {
            return "candidate.sourceRef 必须引用快照 fact/memory";
        }
        if (candidate.dedupeKey() == null || !DEDUPE_KEY.matcher(candidate.dedupeKey()).matches()) {
            return "candidate.dedupeKey 必须是 64 位小写 sha256 hex";
        }
        return null;
    }

    private static UUID leaseToken(LeaseRequest body) {
        return body == null ? null : parseUuid(body.leaseToken());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static ResponseEntity<Object> noStore(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code) {
        return ResponseEntity.status(code.httpStatus()).cacheControl(CacheControl.noStore())
                .body(InternalAiErrorResponse.of(code));
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).cacheControl(CacheControl.noStore())
                .body(InternalAiErrorResponse.of(code, message));
    }

    // ------------------------------------------------------------------
    // DTO（camelCase）
    // ------------------------------------------------------------------

    record ClaimRequest(String taskType, Long epoch, String workerId) {
    }

    /** {@link #toString()} 不含令牌。 */
    record ClaimResponse(String leaseToken, String leaseExpiresAt, String token) {
        @Override
        public String toString() {
            return "ClaimResponse[leaseExpiresAt=" + leaseExpiresAt + ", token=***]";
        }
    }

    record LeaseRequest(Long epoch, String leaseToken) {
    }

    record HeartbeatResponse(String leaseExpiresAt, boolean cancelRequested, String token) {
        @Override
        public String toString() {
            return "HeartbeatResponse[leaseExpiresAt=" + leaseExpiresAt
                    + ", cancelRequested=" + cancelRequested + ", token=***]";
        }
    }

    record SnapshotResponse(int schemaVersion, UUID jobId, UUID projectId, int attempt, long leaseEpoch,
            String snapshotHash, List<AiJobService.Fact> facts,
            List<AiJobService.ExistingMemory> existingMemories) {
    }

    record ResultRequest(Long epoch, String leaseToken, Object payload) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ResultPayload(Integer schemaVersion, List<Candidate> candidates) {
    }

    record Candidate(String kind, String title, String content, BigDecimal confidence,
            String sourceRef, String dedupeKey) {
    }

    record ResultResponse(String status, int accepted, int duplicates) {
    }

    record FailRequest(Long epoch, String leaseToken, String errorCode, Boolean retryable) {
    }

    record FailResponse(String status) {
    }
}
