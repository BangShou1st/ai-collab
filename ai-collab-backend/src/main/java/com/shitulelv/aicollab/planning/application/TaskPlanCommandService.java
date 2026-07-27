package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.api.SaveTaskPlanVersionRequest;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.domain.ValidationResult;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskPlanCommandService {
    private final ProjectAccessGuard access;
    private final TaskPlanRepository repository;
    private final TaskPlanGenerationOrchestrator orchestrator;
    private final TaskPlanDraftValidator validator;
    private final JdbcTemplate jdbc;
    private final PlanningGenerationQuotaService quotaService;
    private final PlanningAttemptThrottle attemptThrottle;
    private final AuditService audit;

    public TaskPlanCommandService(ProjectAccessGuard access, TaskPlanRepository repository,
                                  TaskPlanGenerationOrchestrator orchestrator,
                                  TaskPlanDraftValidator validator, JdbcTemplate jdbc,
                                  PlanningGenerationQuotaService quotaService,
                                  PlanningAttemptThrottle attemptThrottle, AuditService audit) {
        this.access = access; this.repository = repository; this.orchestrator = orchestrator;
        this.validator = validator; this.jdbc = jdbc;
        this.quotaService = quotaService;
        this.attemptThrottle = attemptThrottle;
        this.audit = audit;
    }

    /**
     * P2-4 fix: validate before rate limiting. Invalid requests do not consume quota.
     * R7 fix: pass actor (not plan.createdBy) to orchestrator and repository.
     * Phase 08: Use separate quota and throttle services.
     */
    public TaskPlanRecord create(UUID projectId, CreateTaskPlanRequest request, UUID actor) {
        access.requireAdmin(projectId, actor);
        validateCreate(projectId, request);
        attemptThrottle.check(actor);
        quotaService.checkQuota(actor);
        TaskPlanRecord plan = repository.create(projectId, actor, request);
        safeAudit(projectId, actor, "TASK_PLAN_CREATED", "AI_TASK_PLAN", plan.id());
        orchestrator.dispatch(plan, actor, false);
        return plan;
    }

    /**
     * C4: Use cancelAndReturnAttemptId to get the actual attemptId from the transaction,
     * preventing stale-read race where skeleton→detail transition changes the active attempt.
     */
    public TaskPlanRecord cancel(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        UUID actualAttemptId = repository.cancelAndReturnAttemptId(projectId, planId);
        if (actualAttemptId != null) orchestrator.cancelFuture(actualAttemptId);
        safeAudit(projectId, actor, "TASK_PLAN_CANCELED", "AI_TASK_PLAN", planId);
        return repository.require(projectId, planId);
    }

    /** F5 fix: rate limit BEFORE state change. Invalid requests do not consume quota. */
    public TaskPlanRecord retryDetail(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        // Pre-check state without modifying
        TaskPlanRecord current = repository.require(projectId, planId);
        if (!List.of("DETAIL_GENERATION_FAILED").contains(current.status().name())) {
            throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        }
        attemptThrottle.check(actor);
        quotaService.checkQuota(actor);
        TaskPlanRecord plan = repository.startGeneration(projectId, planId, actor, true);
        safeAudit(projectId, actor, "TASK_PLAN_DETAIL_RETRIED", "AI_TASK_PLAN", planId);
        orchestrator.dispatch(plan, actor, true);
        return plan;
    }

    /** F5 fix: rate limit BEFORE state change. Invalid requests do not consume quota. */
    public TaskPlanRecord regenerate(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        // Pre-check state without modifying
        TaskPlanRecord current = repository.require(projectId, planId);
        if (!List.of("READY", "FAILED", "DETAIL_GENERATION_FAILED", "CANCELED")
                .contains(current.status().name())) {
            throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        }
        attemptThrottle.check(actor);
        quotaService.checkQuota(actor);
        TaskPlanRecord plan = repository.startGeneration(projectId, planId, actor, false);
        safeAudit(projectId, actor, "TASK_PLAN_REGENERATED", "AI_TASK_PLAN", planId);
        orchestrator.dispatch(plan, actor, false);
        return plan;
    }

    /**
     * C9+P2-3: manual save is transactional — lock relevant member rows before validation
     * to prevent member removal between validation and version write.
     */
    @Transactional
    public UUID save(UUID projectId, UUID planId, SaveTaskPlanVersionRequest request, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        TaskPlanDraft base = repository.draft(repository.requireVersion(
                projectId, planId, request.baseVersionId()));
        TaskPlanDraft normalized = new TaskPlanDraft(
                request.draft().summary(), request.draft().assumptions(), request.draft().risks(),
                request.draft().milestones(), request.draft().tasks(), base.sources());
        // C9: Lock member rows referenced in the draft before validation
        Set<UUID> memberIds = collectMemberIds(normalized);
        repository.lockProjectMembers(projectId, memberIds);
        var validation = ensureValid(projectId, plan, normalized);
        UUID version = repository.appendVersion(projectId, planId, request.baseVersionId(),
                "MANUAL_EDIT", request.baseVersionId(), normalized, actor, validation);
        safeAudit(projectId, actor, "TASK_PLAN_VERSION_SAVED", "AI_TASK_PLAN_VERSION", version);
        return version;
    }

    @Transactional
    public UUID restore(UUID projectId, UUID planId, UUID versionId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        TaskPlanVersionRecord source = repository.requireVersion(projectId, planId, versionId);
        TaskPlanDraft draft = repository.draft(source);

        // Phase 08: Lock member rows referenced in the draft before validation
        Set<UUID> memberIds = collectMemberIds(draft);
        repository.lockProjectMembers(projectId, memberIds);

        var validation = ensureValid(projectId, plan, draft);
        UUID restored = repository.appendVersion(projectId, planId, plan.latestVersionId(),
                "RESTORED", versionId, draft, actor, validation);
        safeAudit(projectId, actor, "TASK_PLAN_VERSION_RESTORED", "AI_TASK_PLAN_VERSION", restored);
        return restored;
    }

    @Transactional
    public void delete(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.lock(projectId, planId);
        if (!List.of("READY", "FAILED", "DETAIL_GENERATION_FAILED", "CANCELED")
                .contains(plan.status().name())) throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        jdbc.update("DELETE FROM ai_task_plan WHERE id=?", planId);
        safeAudit(projectId, actor, "TASK_PLAN_DELETED", "AI_TASK_PLAN", planId);
    }

    private void validateCreate(UUID projectId, CreateTaskPlanRequest request) {
        if (!Set.of(10, 20, 30, 40).contains(request.maxTaskCount())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "maxTaskCount 仅允许 10、20、30、40");
        }
        if (request.planStartDate().isAfter(request.planDueDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "规划日期范围无效");
        }
        List<UUID> documents = request.documentIds() == null ? List.of() : request.documentIds();
        if (documents.size() > 10 || new HashSet<>(documents).size() != documents.size()) {
            throw new BusinessException(ErrorCode.PLANNING_DOCUMENT_LIMIT_EXCEEDED);
        }
        Integer valid = documents.isEmpty() ? 0 : jdbc.queryForObject("""
                SELECT count(*) FROM project_document
                WHERE project_id=? AND status='READY' AND id IN (%s)
                """.formatted("?,".repeat(documents.size()).replaceFirst(",$", "")),
                Integer.class, concat(projectId, documents));
        if (valid == null || valid != documents.size()) throw new BusinessException(ErrorCode.PLANNING_DOCUMENT_NOT_READY);
        LocalDate[] dates = jdbc.queryForObject("SELECT start_date,due_date FROM project WHERE id=?",
                (rs, row) -> new LocalDate[]{rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, projectId);
        if (dates != null && (dates[0] != null && request.planStartDate().isBefore(dates[0])
                || dates[1] != null && request.planDueDate().isAfter(dates[1]))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "规划日期超出项目范围");
        }
    }

    private ValidationResult ensureValid(UUID projectId, TaskPlanRecord plan, TaskPlanDraft draft) {
        Set<UUID> members = new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM project_member WHERE project_id=?", UUID.class, projectId));
        LocalDate[] projectDates = jdbc.queryForObject("SELECT start_date,due_date FROM project WHERE id=?",
                (rs, row) -> new LocalDate[]{rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, projectId);
        var result = validator.validate(new ValidationContext(projectDates[0], projectDates[1],
                plan.planStartDate(), plan.planDueDate(), plan.maxTaskCount(), members, Set.of()), draft);
        if (!result.valid()) throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED,
                "规划校验失败：" + String.join(",", result.errorCodes()));
        return result;
    }

    /** C9: Collect all member UUIDs referenced in the draft for locking. */
    private static Set<UUID> collectMemberIds(com.shitulelv.aicollab.planning.domain.TaskPlanDraft draft) {
        Set<UUID> ids = new HashSet<>();
        for (var task : draft.tasks()) {
            if (task.suggestedAssigneeId() != null) ids.add(task.suggestedAssigneeId());
            if (task.assigneeId() != null) ids.add(task.assigneeId());
        }
        return ids;
    }

    private static Object[] concat(UUID projectId, List<UUID> ids) {
        Object[] values = new Object[ids.size() + 1]; values[0] = projectId;
        for (int i = 0; i < ids.size(); i++) values[i + 1] = ids.get(i);
        return values;
    }

    private void safeAudit(UUID projectId, UUID actor, String action, String entityType, UUID entityId) {
        try {
            audit.write(projectId, actor, action, entityType, entityId);
        } catch (RuntimeException ignored) {
        }
    }
}
