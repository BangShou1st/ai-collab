package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskPlanQueryService {
    private final ProjectAccessGuard access;
    private final TaskPlanRepository repository;
    private final JdbcTemplate jdbc;
    public TaskPlanQueryService(ProjectAccessGuard access, TaskPlanRepository repository, JdbcTemplate jdbc) {
        this.access = access; this.repository = repository; this.jdbc = jdbc;
    }
    public List<TaskPlanRecord> list(UUID projectId, String status, int page, int size, UUID actor) {
        access.requireMember(projectId, actor);
        int safeSize = Math.max(1, Math.min(size, 100));
        return repository.list(projectId, status, safeSize, Math.max(0, page) * safeSize);
    }
    public Map<String, Object> detail(UUID projectId, UUID planId, UUID actor) {
        ProjectRole role = access.requireMember(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        boolean write = role.isAdminOrOwner();
        boolean ready = plan.status().name().equals("READY");

        Map<String, Object> activeAttempt = null;
        if (plan.activeAttemptId() != null) {
            var attempt = jdbc.queryForMap(
                    "SELECT * FROM ai_task_plan_attempt WHERE id=?", plan.activeAttemptId());
            activeAttempt = Map.of("id", attempt.get("id"), "status", attempt.get("status"),
                    "stage", attempt.get("stage"), "startedAt", attempt.get("started_at"));
        }

        Map<String, Object> lastFailure = null;
        var failures = jdbc.queryForList(
                "SELECT error_code,error_summary,finished_at FROM ai_task_plan_attempt WHERE plan_id=? AND status='FAILED' ORDER BY finished_at DESC LIMIT 1",
                planId);
        if (!failures.isEmpty()) {
            var f = failures.getFirst();
            lastFailure = Map.of("errorCode", f.get("error_code"), "errorSummary", f.get("error_summary"),
                    "finishedAt", f.get("finished_at"));
        }

        var confirmations = jdbc.queryForList(
                "SELECT id,status,created_at,completed_at FROM ai_task_plan_confirmation WHERE plan_id=? ORDER BY created_at DESC LIMIT 1",
                planId);
        Map<String, Object> confirmation = confirmations.isEmpty() ? null : Map.of(
                "id", confirmations.getFirst().get("id"), "status", confirmations.getFirst().get("status"),
                "createdAt", confirmations.getFirst().get("created_at"),
                "completedAt", confirmations.getFirst().get("completed_at"));

        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("plan", plan);
        result.put("permissions", Map.of(
                "canEdit", write && ready, "canCancel", write && plan.status().isGenerating(),
                "canRetryDetail", write && plan.status().name().equals("DETAIL_GENERATION_FAILED"),
                "canRegenerate", write && List.of("READY","FAILED","DETAIL_GENERATION_FAILED","CANCELED").contains(plan.status().name()),
                "canConfirm", write && ready, "canDelete", write && List.of("READY","FAILED","DETAIL_GENERATION_FAILED","CANCELED").contains(plan.status().name()),
                "canRestore", write && ready));
        result.put("activeAttempt", activeAttempt);
        result.put("lastFailure", lastFailure);
        result.put("confirmation", confirmation);
        return result;
    }
    public List<TaskPlanVersionRecord> versions(UUID projectId, UUID planId, UUID actor) {
        access.requireMember(projectId, actor); return repository.versions(projectId, planId);
    }
    public Map<String, Object> version(UUID projectId, UUID planId, UUID versionId, UUID actor) {
        access.requireMember(projectId, actor);
        TaskPlanVersionRecord version = repository.requireVersion(projectId, planId, versionId);
        return Map.of("version", version, "draft", repository.draft(version));
    }
}
