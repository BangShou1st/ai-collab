package com.shitulelv.aicollab.internal.ai.job;

import com.shitulelv.aicollab.agent.job.AiJobCandidate;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Python → Java 内部 Job 端点（W5 冻结 §3.3）：
 * {@code GET /internal/ai/v1/jobs/{id}/snapshot}、{@code POST .../heartbeat}、
 * {@code POST .../results}。
 *
 * <p><b>鉴权</b>：由 {@link InternalAiTokenFilter} 在进入 MVC 前验签并按路径要求
 * {@code job:snapshot}/{@code job:heartbeat}/{@code job:results} scope；验签后的 claims
 * 通过 request attribute 传入。本控制器再校验 claims 的 {@code jobId/attempt/leaseEpoch}
 * 与路径/请求一致（不一致 403）。static 模式没有 claims → job 端点 503 fail closed
 * （job 必须 Ed25519 job token）。</p>
 *
 * <p><b>边界</b>：响应不包含成员名单、文档正文、Prompt 或令牌；结果只写
 * {@code ai_job_result}（review_status=PENDING_REVIEW），不存在候选发布路径。</p>
 */
@RestController
@RequestMapping("/internal/ai/v1/jobs")
public class InternalAiJobController {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_CANDIDATES = 50;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 2000;
    private static final int MAX_SOURCE_REF_CHARS = 200;
    private static final Set<String> CANDIDATE_KINDS =
            Set.of("DECISION", "PREFERENCE", "CONSTRAINT", "LESSON");
    private static final Pattern DEDUPE_KEY = Pattern.compile("^[0-9a-f]{64}$");

    private final AiJobService jobs;

    public InternalAiJobController(AiJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}/snapshot")
    public ResponseEntity<Object> snapshot(@PathVariable UUID id, HttpServletRequest request) {
        Map<String, Object> rawClaims = rawClaims(request);
        if (rawClaims.isEmpty()) {
            return unavailable();
        }
        AiJobTokenClaims claims = AiJobTokenClaims.from(rawClaims);
        if (claims == null || !claims.complete() || !claims.boundToPath(id)) {
            return forbidden();
        }
        AiJobService.Snapshot snapshot = jobs.snapshot(id, claims.attempt(), claims.leaseEpoch());
        List<InternalAiJobDtos.Fact> facts = snapshot.facts().stream()
                .map(fact -> new InternalAiJobDtos.Fact(fact.ref(), fact.kind(), fact.text()))
                .toList();
        List<InternalAiJobDtos.ExistingMemory> memories = snapshot.existingMemories().stream()
                .map(memory -> new InternalAiJobDtos.ExistingMemory(memory.id(), memory.type(),
                        memory.title(), memory.content()))
                .toList();
        return ResponseEntity.ok(new InternalAiJobDtos.SnapshotResponse(SCHEMA_VERSION,
                snapshot.jobId(), snapshot.projectId(), snapshot.attempt(), snapshot.leaseEpoch(),
                snapshot.snapshotHash(), facts, memories));
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Object> heartbeat(@PathVariable UUID id,
            @RequestBody(required = false) InternalAiJobDtos.HeartbeatRequest body,
            HttpServletRequest request) {
        if (body == null || body.schemaVersion() == null || body.schemaVersion() != SCHEMA_VERSION) {
            return schemaViolation("schemaVersion 必须为 1");
        }
        if (body.attempt() == null || body.attempt() < 1 || body.leaseEpoch() == null) {
            return invalidRequest("attempt/leaseEpoch 必填");
        }
        Map<String, Object> rawClaims = rawClaims(request);
        if (rawClaims.isEmpty()) {
            return unavailable();
        }
        AiJobTokenClaims claims = AiJobTokenClaims.from(rawClaims);
        if (claims == null || !claims.complete()
                || !claims.boundTo(id, body.attempt(), body.leaseEpoch())) {
            return forbidden();
        }
        AiJobService.Heartbeat heartbeat = jobs.heartbeat(id, body.attempt(), body.leaseEpoch());
        return ResponseEntity.ok(new InternalAiJobDtos.HeartbeatResponse(SCHEMA_VERSION,
                heartbeat.status().name(),
                heartbeat.leaseExpiresAt() == null ? null : heartbeat.leaseExpiresAt().toString()));
    }

    @PostMapping("/{id}/results")
    public ResponseEntity<Object> results(@PathVariable UUID id,
            @RequestBody(required = false) InternalAiJobDtos.ResultsRequest body,
            HttpServletRequest request) {
        if (body == null || body.schemaVersion() == null || body.schemaVersion() != SCHEMA_VERSION) {
            return schemaViolation("schemaVersion 必须为 1");
        }
        if (body.attempt() == null || body.attempt() < 1 || body.leaseEpoch() == null) {
            return invalidRequest("attempt/leaseEpoch 必填");
        }
        List<InternalAiJobDtos.Candidate> candidates =
                body.candidates() == null ? List.of() : body.candidates();
        if (candidates.size() > MAX_CANDIDATES) {
            return invalidRequest("candidates 不能超过 50 条");
        }
        for (InternalAiJobDtos.Candidate candidate : candidates) {
            String violation = validate(candidate);
            if (violation != null) {
                return schemaViolation(violation);
            }
        }
        Map<String, Object> rawClaims = rawClaims(request);
        if (rawClaims.isEmpty()) {
            return unavailable();
        }
        AiJobTokenClaims claims = AiJobTokenClaims.from(rawClaims);
        if (claims == null || !claims.complete()
                || !claims.boundTo(id, body.attempt(), body.leaseEpoch())) {
            return forbidden();
        }
        List<AiJobCandidate> mapped = new ArrayList<>(candidates.size());
        for (InternalAiJobDtos.Candidate candidate : candidates) {
            mapped.add(new AiJobCandidate(candidate.kind(), candidate.title(), candidate.content(),
                    candidate.confidence(), candidate.sourceRef(), candidate.dedupeKey()));
        }
        AiJobService.ResultsOutcome outcome =
                jobs.submitResults(id, body.attempt(), body.leaseEpoch(), mapped);
        return ResponseEntity.ok(new InternalAiJobDtos.ResultsResponse(SCHEMA_VERSION,
                outcome.jobId(), outcome.status().name(), outcome.accepted(), outcome.duplicates()));
    }

    /** 候选契约校验（§3.5）；返回 null 表示合法。 */
    private static String validate(InternalAiJobDtos.Candidate candidate) {
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

    private static Map<String, Object> rawClaims(HttpServletRequest request) {
        Object value = request.getAttribute(InternalAiTokenFilter.VERIFIED_CLAIMS_ATTRIBUTE);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> claims = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    claims.put(key.toString(), item);
                }
            });
            return claims;
        }
        return Map.of();
    }

    private static ResponseEntity<Object> unavailable() {
        return error(InternalAiErrorCode.MODEL_UNAVAILABLE,
                "内部 Job 端点需要 Ed25519 job token（static 模式无 claims，fail closed）");
    }

    private static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(403).body(
                InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST,
                        "job token claims 与路径/请求不一致"));
    }

    private static ResponseEntity<Object> invalidRequest(String message) {
        return error(InternalAiErrorCode.INVALID_REQUEST, message);
    }

    private static ResponseEntity<Object> schemaViolation(String message) {
        return error(InternalAiErrorCode.SCHEMA_VIOLATION, message);
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(InternalAiErrorResponse.of(code, message));
    }
}
