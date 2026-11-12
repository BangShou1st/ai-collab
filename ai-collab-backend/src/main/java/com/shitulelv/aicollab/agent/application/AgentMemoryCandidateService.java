package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryCandidateView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.job.AiJobCandidate;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.agent.job.AiJobResultRecord;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dreaming 候选审核与人工发布（W5）。
 *
 * <p>候选只能来自 {@code ai_job_result} 且初始状态为 PENDING_REVIEW。批准会在同一事务内：
 * 行锁候选 → 解析并复核载荷 → 复用 {@link AgentMemoryService} 创建 ACTIVE 记忆
 * （source_type=AI_JOB_RESULT, source_id=resultId）→ CAS 结果状态为 ACCEPTED → 审计。
 * 任一步失败整体回滚。拒绝只 CAS 为 REJECTED，不创建记忆。</p>
 *
 * <p>本服务没有自动发布入口；只有项目管理员显式调用 approve。</p>
 */
@Service
public class AgentMemoryCandidateService {

    public static final String REVIEW_PENDING = "PENDING_REVIEW";
    public static final String REVIEW_ACCEPTED = "ACCEPTED";
    public static final String REVIEW_REJECTED = "REJECTED";
    static final String SOURCE_TYPE = "AI_JOB_RESULT";

    private static final Set<String> REVIEW_STATUSES =
            Set.of(REVIEW_PENDING, REVIEW_ACCEPTED, REVIEW_REJECTED);
    private static final Set<String> KINDS =
            Set.of("DECISION", "PREFERENCE", "CONSTRAINT", "LESSON");
    private static final Pattern DEDUPE_KEY = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_MEMORY_TITLE_CHARS = 160;

    private final AiJobRepository jobs;
    private final AgentMemoryService memories;
    private final ProjectAccessGuard access;
    private final AuditService audit;
    private final ObjectMapper json;

    public AgentMemoryCandidateService(AiJobRepository jobs, AgentMemoryService memories,
            ProjectAccessGuard access, AuditService audit, ObjectMapper json) {
        this.jobs = jobs;
        this.memories = memories;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    /** 管理员按状态查看候选；默认只看待审核。 */
    public List<AgentMemoryCandidateView> list(UUID projectId, UUID userId, String reviewStatus) {
        access.requireAdmin(projectId, userId);
        String status = normalizeStatus(reviewStatus);
        return jobs.listResults(projectId, status, 200).stream()
                .map(result -> candidateView(result, parse(result)))
                .toList();
    }

    /**
     * 批准候选并发布为记忆。重复批准返回同一来源已发布的记忆；其它非 PENDING 状态拒绝。
     */
    @Transactional
    public AgentMemoryView approve(UUID projectId, UUID candidateId, UUID userId, String reason) {
        access.requireAdmin(projectId, userId);
        AiJobResultRecord result = requireLocked(projectId, candidateId);
        if (REVIEW_ACCEPTED.equals(result.reviewStatus())) {
            return jobs.findMemoryBySource(projectId, SOURCE_TYPE, result.id())
                    .flatMap(id -> memories.findForAdmin(projectId, id, userId))
                    .orElseThrow(() -> conflict("候选已批准但找不到来源记忆"));
        }
        requirePending(result);
        AiJobCandidate candidate = parse(result);
        validate(candidate);

        AgentMemoryView created = memories.create(projectId, userId,
                new AgentMemoryRequest(candidate.kind(), candidate.title(), candidate.content(),
                        SOURCE_TYPE, result.id(), 0, null, safeReason(reason)));
        if (!jobs.markReviewed(projectId, result.id(), REVIEW_PENDING, REVIEW_ACCEPTED)) {
            throw conflict("候选审核状态已被其它请求修改");
        }
        audit(projectId, userId, "AGENT_MEMORY_CANDIDATE_APPROVED", result, candidate, reason);
        return created;
    }

    /** 拒绝候选；拒绝原因必须显式给出，绝不自动发布。 */
    @Transactional
    public AgentMemoryCandidateView reject(UUID projectId, UUID candidateId, UUID userId,
            String reason) {
        access.requireAdmin(projectId, userId);
        String normalizedReason = requireReason(reason);
        AiJobResultRecord result = requireLocked(projectId, candidateId);
        if (REVIEW_REJECTED.equals(result.reviewStatus())) {
            return candidateView(result, parse(result));
        }
        requirePending(result);
        AiJobCandidate candidate = parse(result);
        if (!jobs.markReviewed(projectId, result.id(), REVIEW_PENDING, REVIEW_REJECTED)) {
            throw conflict("候选审核状态已被其它请求修改");
        }
        audit(projectId, userId, "AGENT_MEMORY_CANDIDATE_REJECTED", result, candidate,
                normalizedReason);
        return candidateView(new AiJobResultRecord(result.id(), result.jobId(),
                result.attemptNo(), result.resultKind(), result.dedupeKey(), result.payloadJson(),
                REVIEW_REJECTED, result.createdAt()), candidate);
    }

    /** 管理视图读取已发布记忆；不存在或跨项目返回统一错误，不泄露其它项目。 */
    public AgentMemoryView findPublished(UUID projectId, UUID userId, UUID memoryId) {
        access.requireAdmin(projectId, userId);
        return memories.findForAdmin(projectId, memoryId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MEMORY_NOT_FOUND));
    }

    private AiJobResultRecord requireLocked(UUID projectId, UUID candidateId) {
        return jobs.lockResultForReview(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AGENT_MEMORY_CANDIDATE_NOT_FOUND));
    }

    private static void requirePending(AiJobResultRecord result) {
        if (!REVIEW_PENDING.equals(result.reviewStatus())) {
            throw conflict("候选已被批准或拒绝");
        }
    }

    private AiJobCandidate parse(AiJobResultRecord result) {
        try {
            AiJobCandidate candidate = json.readValue(result.payloadJson(), AiJobCandidate.class);
            if (candidate == null) {
                throw invalid("候选载荷为空");
            }
            return candidate;
        } catch (JsonProcessingException failure) {
            throw invalid("候选载荷不是合法 JSON");
        }
    }

    private static void validate(AiJobCandidate candidate) {
        if (candidate.kind() == null || !KINDS.contains(candidate.kind())) {
            throw invalid("候选 kind 不合法");
        }
        if (candidate.title() == null || candidate.title().isBlank()
                || candidate.title().strip().length() > MAX_MEMORY_TITLE_CHARS) {
            throw invalid("候选 title 超出记忆契约");
        }
        if (candidate.content() == null || candidate.content().isBlank()
                || candidate.content().strip().length() > 2000) {
            throw invalid("候选 content 超出记忆契约");
        }
        BigDecimal confidence = candidate.confidence();
        if (confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("候选 confidence 超出 0..1");
        }
        String sourceRef = candidate.sourceRef();
        if (sourceRef == null || sourceRef.isBlank() || sourceRef.length() > 200
                || !(sourceRef.startsWith("fact:") || sourceRef.startsWith("memory:"))) {
            throw invalid("候选 sourceRef 不是快照事实或记忆引用");
        }
        if (candidate.dedupeKey() == null || !DEDUPE_KEY.matcher(candidate.dedupeKey()).matches()) {
            throw invalid("候选 dedupeKey 不是 64 位小写 sha256");
        }
    }

    private AgentMemoryCandidateView candidateView(AiJobResultRecord result,
            AiJobCandidate candidate) {
        return new AgentMemoryCandidateView(result.id(), result.jobId(), result.attemptNo(),
                candidate.kind(), candidate.title(), candidate.content(), candidate.confidence(),
                candidate.sourceRef(), result.reviewStatus(), result.createdAt());
    }

    private void audit(UUID projectId, UUID userId, String action, AiJobResultRecord result,
            AiJobCandidate candidate, String reason) {
        if (audit == null) {
            return;
        }
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("jobId", result.jobId().toString());
        detail.put("kind", candidate.kind());
        detail.put("reviewStatus", action.endsWith("APPROVED") ? REVIEW_ACCEPTED : REVIEW_REJECTED);
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason);
        }
        audit.write(projectId, userId, action, "AI_JOB_RESULT", result.id(), detail);
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return REVIEW_PENDING;
        }
        String status = raw.strip();
        if (!REVIEW_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_CANDIDATE_INVALID,
                    "reviewStatus 必须是 PENDING_REVIEW/ACCEPTED/REJECTED");
        }
        return status;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_CANDIDATE_INVALID,
                    "拒绝候选必须给出 reason");
        }
        if (reason.length() > 500) {
            throw new BusinessException(ErrorCode.AGENT_MEMORY_CANDIDATE_INVALID,
                    "reason 不能超过 500 字符");
        }
        return reason.strip();
    }

    private static String safeReason(String reason) {
        return reason == null ? null : reason.strip();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.AGENT_MEMORY_CANDIDATE_CONFLICT, message);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.AGENT_MEMORY_CANDIDATE_INVALID, message);
    }
}
