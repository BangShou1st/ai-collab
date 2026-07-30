package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.PlanningRequestHash;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskPlanConfirmationService {
    private final ProjectAccessGuard access;
    private final TaskPlanRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskPlanDraftValidator validator;
    private final TaskPlanIssueRepository issueRepo;
    private final AuditService audit;
    private final TaskPlanActionPolicy actionPolicy;
    private final NotificationApplicationService notifications;

    public TaskPlanConfirmationService(ProjectAccessGuard access, TaskPlanRepository repository,
                                       JdbcTemplate jdbc, PlatformTransactionManager manager,
                                       TaskPlanDraftValidator validator, TaskPlanIssueRepository issueRepo,
                                       AuditService audit, TaskPlanActionPolicy actionPolicy,
                                       NotificationApplicationService notifications) {
        this.access = access; this.repository = repository; this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.validator = validator;
        this.issueRepo = issueRepo;
        this.audit = audit;
        this.actionPolicy = actionPolicy;
        this.notifications = notifications;
    }

    public Map<String, Object> confirm(UUID projectId, UUID planId, UUID versionId,
                                       UUID idempotencyKey, UUID actor) {
        access.requireAdmin(projectId, actor);
        String hash = PlanningRequestHash.confirmation(projectId, planId, versionId);
        Claim claim = transactions.execute(status -> claim(projectId, planId, versionId, idempotencyKey, hash, actor));
        if (claim.replay() != null) return claim.replay();
        try {
            return transactions.execute(status -> land(projectId, planId, versionId, claim.id(), actor));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE ai_task_plan SET status='READY',updated_at=now() WHERE id=? AND status='CONFIRMING'", planId);
                jdbc.update("""
                        UPDATE ai_task_plan_confirmation SET status='FAILED',error_code='INTERNAL_ERROR',
                          error_summary='规划落地失败，未创建部分数据',completed_at=now(),updated_at=now()
                        WHERE id=? AND status='PROCESSING'
                        """, claim.id());
            });
            safeAudit(projectId, actor, "TASK_PLAN_CONFIRMATION_FAILED", planId);
            throw failure;
        }
    }

    private Claim claim(UUID projectId, UUID planId, UUID versionId, UUID key, String hash, UUID actor) {
        TaskPlanRecord plan = repository.lock(projectId, planId);
        List<Map<String, Object>> byKey = jdbc.queryForList("""
                SELECT * FROM ai_task_plan_confirmation WHERE project_id=? AND idempotency_key=? FOR UPDATE
                """, projectId, key);
        if (!byKey.isEmpty()) {
            Map<String, Object> row = byKey.getFirst();
            if (!hash.equals(row.get("request_hash"))) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCESS".equals(row.get("status")) || "PROCESSING".equals(row.get("status"))) {
                return new Claim((UUID) row.get("id"), response(row));
            }
            // FAILED retry: revalidate all preconditions
            if ("FAILED".equals(row.get("status"))) {
                // P8: Check READY_WITH_ISSUES first for specific error message
                if (plan.status() == TaskPlanStatus.READY_WITH_ISSUES) {
                    throw new BusinessException(ErrorCode.TASK_PLAN_HAS_BLOCKING_ISSUES);
                }
                actionPolicy.require(plan.status(), TaskPlanActionPolicy.Action.CONFIRM);
                if (!versionId.equals(row.get("version_id"))
                        || !versionId.equals(plan.latestVersionId())) {
                    throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
                }
                // Task 10: Block confirmation while plan issues remain
                int blockingCount = issueRepo.countUnresolvedBlocking(planId, (UUID) row.get("version_id"));
                if (blockingCount > 0) {
                    throw new BusinessException(ErrorCode.TASK_PLAN_HAS_BLOCKING_ISSUES);
                }
                Integer activeGens = jdbc.queryForObject("""
                        SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id=? AND status IN ('QUEUED','RUNNING')
                        """, Integer.class, planId);
                if (activeGens != null && activeGens > 0) throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
                jdbc.update("UPDATE ai_task_plan_confirmation SET status='PROCESSING',error_code=NULL,error_summary=NULL,updated_at=now() WHERE id=?",
                        row.get("id"));
                jdbc.update("UPDATE ai_task_plan SET status='CONFIRMING',updated_at=now() WHERE id=?", planId);
                return new Claim((UUID) row.get("id"), null);
            }
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM ai_task_plan_confirmation WHERE plan_id=? AND status='SUCCESS'", planId);
        if (!existing.isEmpty()) return new Claim((UUID) existing.getFirst().get("id"), response(existing.getFirst()));
        // F6: Check for ANY existing confirmation (FAILED/PROCESSING) with different key → stable 409
        List<Map<String, Object>> anyExisting = jdbc.queryForList(
                "SELECT * FROM ai_task_plan_confirmation WHERE plan_id=?", planId);
        if (!anyExisting.isEmpty()) {
            // There's a FAILED/PROCESSING confirmation for this plan but with a different key
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "该规划已有确认记录，请使用原始 Idempotency-Key 重试");
        }
        // P8: Check READY_WITH_ISSUES first for specific error message
        if (plan.status() == TaskPlanStatus.READY_WITH_ISSUES) {
            throw new BusinessException(ErrorCode.TASK_PLAN_HAS_BLOCKING_ISSUES);
        }
        actionPolicy.require(plan.status(), TaskPlanActionPolicy.Action.CONFIRM);
        if (!versionId.equals(plan.latestVersionId())) {
            throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
        }
        // Task 10: Block confirmation while plan issues remain
        if (plan.latestVersionId() != null) {
            int blockingCount = issueRepo.countUnresolvedBlocking(planId, plan.latestVersionId());
            if (blockingCount > 0) {
                throw new BusinessException(ErrorCode.TASK_PLAN_HAS_BLOCKING_ISSUES);
            }
        }
        repository.requireVersion(projectId, planId, versionId);
        UUID confirmation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(id,project_id,plan_id,version_id,idempotency_key,
                  request_hash,status,created_by) VALUES (?,?,?,?,?,?,'PROCESSING',?)
                """, confirmation, projectId, planId, versionId, key, hash, actor);
        jdbc.update("UPDATE ai_task_plan SET status='CONFIRMING',updated_at=now() WHERE id=?", planId);
        return new Claim(confirmation, null);
    }

    private Map<String, Object> land(UUID projectId, UUID planId, UUID versionId, UUID confirmation, UUID actor) {
        TaskPlanRecord plan = repository.lock(projectId, planId);
        if (plan.status() != TaskPlanStatus.CONFIRMING) throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        if (!versionId.equals(plan.latestVersionId())) {
            throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
        }
        if (issueRepo.countUnresolvedBlocking(planId, versionId) > 0) {
            throw new BusinessException(ErrorCode.TASK_PLAN_HAS_BLOCKING_ISSUES);
        }
        TaskPlanVersionRecord version = repository.requireVersion(projectId, planId, versionId);
        TaskPlanDraft draft = repository.draft(version);
        // C9: Lock member rows referenced in the draft before validation
        java.util.Set<UUID> memberIds = new java.util.HashSet<>();
        for (var task : draft.tasks()) {
            if (task.suggestedAssigneeId() != null) memberIds.add(task.suggestedAssigneeId());
            if (task.assigneeId() != null) memberIds.add(task.assigneeId());
        }
        repository.lockProjectMembers(projectId, memberIds);
        String confirmationStatus = jdbc.queryForObject("""
                SELECT status FROM ai_task_plan_confirmation WHERE id=? AND plan_id=? FOR UPDATE
                """, String.class, confirmation, planId);
        if (!"PROCESSING".equals(confirmationStatus)) {
            throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        }
        var validation = validator.validate(repository.validationContext(plan), draft);
        if (!validation.valid()) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED,
                    "规划校验失败：" + String.join(",", validation.errorCodes()));
        }
        Map<String, UUID> milestoneIds = new LinkedHashMap<>();
        for (var milestone : draft.milestones()) {
            UUID id = UUID.randomUUID(); milestoneIds.put(milestone.tempKey(), id);
            jdbc.update("""
                    INSERT INTO milestone(id,project_id,name,description,target_date,status,sort_order,created_by,
                      source_plan_id,source_plan_version_id,source_plan_milestone_key)
                    VALUES (?,?,?,?,?,'PLANNED',?,?,?,?,?)
                    """, id, projectId, milestone.title(),
                    milestone.description() != null ? milestone.description() : milestone.objective(),
                    milestone.targetDate(),
                    milestone.sortOrder(), actor, planId, versionId, milestone.tempKey());
        }
        Map<String, UUID> taskIds = new LinkedHashMap<>();
        for (PlanTask task : draft.tasks()) {
            UUID id = UUID.randomUUID(); taskIds.put(task.tempKey(), id);
            jdbc.update("""
                    INSERT INTO project_task(id,project_id,milestone_id,title,description,status,priority,
                      assignee_id,estimate_hours,start_date,due_date,sort_order,created_by,
                      source_plan_id,source_plan_version_id,source_plan_task_key)
                    VALUES (?,?,?, ?,?,'TODO',?, ?,?,?,?,?, ?,?,?,?)
                    """, id, projectId, milestoneIds.get(task.milestoneTempKey()), task.title(), task.description(),
                    task.priority(), task.assigneeId(), task.estimatedHours(), task.startDate(), task.dueDate(),
                    task.sortOrder(), actor, planId, versionId, task.tempKey());
        }
        int dependencies = 0;
        for (PlanTask task : draft.tasks()) for (String required : task.dependencyTempKeys()) {
            jdbc.update("INSERT INTO task_dependency(task_id,depends_on_task_id) VALUES (?,?)",
                    taskIds.get(task.tempKey()), taskIds.get(required)); dependencies++;
        }
        jdbc.update("""
                UPDATE ai_task_plan SET status='CONFIRMED',confirmed_by=?,confirmed_version_id=?,
                  confirmed_at=now(),active_attempt_id=NULL,updated_at=now() WHERE id=?
                """, actor, versionId, planId);
        jdbc.update("""
                UPDATE ai_task_plan_confirmation SET status='SUCCESS',created_milestone_ids_json=?::jsonb,
                  created_task_ids_json=?::jsonb,created_dependency_count=?,completed_at=now(),updated_at=now()
                WHERE id=?
                """, idsJson(milestoneIds.values()), idsJson(taskIds.values()), dependencies, confirmation);
        audit.write(projectId, actor, "TASK_PLAN_CONFIRMED", "AI_TASK_PLAN", planId,
                Map.of("milestoneCount", milestoneIds.size(),
                        "taskCount", taskIds.size(),
                        "dependencyCount", dependencies));
        notifications.create(projectId, plan.createdBy(),
                "PLAN_CONFIRMED", "AI 规划已确认",
                "规划「" + plan.title() + "」已创建正式任务",
                "AI_TASK_PLAN", planId);
        return Map.of("confirmationId", confirmation, "status", "SUCCESS",
                "milestoneIds", List.copyOf(milestoneIds.values()), "taskIds", List.copyOf(taskIds.values()),
                "dependencyCount", dependencies);
    }

    private static Map<String, Object> response(Map<String, Object> row) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("confirmationId", row.get("id"));
        response.put("status", row.get("status"));
        response.put("milestoneIds", parseJsonArray(row.get("created_milestone_ids_json")));
        response.put("taskIds", parseJsonArray(row.get("created_task_ids_json")));
        response.put("dependencyCount", row.get("created_dependency_count"));
        return response;
    }

    private static List<?> parseJsonArray(Object jsonb) {
        if (jsonb == null) return List.of();
        String json = jsonb.toString();
        if (!json.startsWith("[")) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String idsJson(Iterable<UUID> ids) {
        StringBuilder out = new StringBuilder("["); boolean first = true;
        for (UUID id : ids) { if (!first) out.append(','); out.append('"').append(id).append('"'); first = false; }
        return out.append(']').toString();
    }

    private void safeAudit(UUID projectId, UUID actor, String action, UUID planId) {
        try {
            audit.write(projectId, actor, action, "AI_TASK_PLAN", planId);
        } catch (RuntimeException ignored) {
            // Compensation state is authoritative and must remain committed if audit storage is unavailable.
        }
    }
    private record Claim(UUID id, Map<String, Object> replay) {}
}
