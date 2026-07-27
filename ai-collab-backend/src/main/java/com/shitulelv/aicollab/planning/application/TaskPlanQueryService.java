package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
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
    private final TaskPlanIssueRepository issueRepo;
    private final TaskPlanEventRepository eventRepo;
    private final JdbcTemplate jdbc;
    public TaskPlanQueryService(ProjectAccessGuard access, TaskPlanRepository repository,
                                 TaskPlanIssueRepository issueRepo, TaskPlanEventRepository eventRepo,
                                 JdbcTemplate jdbc) {
        this.access = access; this.repository = repository;
        this.issueRepo = issueRepo; this.eventRepo = eventRepo; this.jdbc = jdbc;
    }
    public List<TaskPlanRecord> list(UUID projectId, String status, int page, int size, UUID actor) {
        access.requireMember(projectId, actor);
        int safeSize = Math.max(1, Math.min(size, 100));
        return repository.list(projectId, status, safeSize, Math.max(0, page) * safeSize);
    }

    /**
     * C2: Returns typed TaskPlanDetailView instead of Map.
     * All nullable fields use explicit null — no Map.of with null values.
     */
    public TaskPlanDetailView detail(UUID projectId, UUID planId, UUID actor) {
        ProjectRole role = access.requireMember(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        boolean write = role.isAdminOrOwner();
        boolean ready = plan.status().name().equals("READY");

        // Active attempt — nullable (QUEUED has null startedAt)
        TaskPlanAttemptView activeAttempt = null;
        if (plan.activeAttemptId() != null) {
            var attempts = jdbc.queryForList(
                    "SELECT * FROM ai_task_plan_attempt WHERE id=?", plan.activeAttemptId());
            if (!attempts.isEmpty()) {
                var a = attempts.getFirst();
                activeAttempt = new TaskPlanAttemptView(
                        str(a.get("id")), str(a.get("stage")), str(a.get("status")),
                        str(a.get("provider")), str(a.get("model")),
                        str(a.get("started_at")), str(a.get("finished_at")),
                        longVal(a.get("latency_ms")), intVal(a.get("prompt_tokens")),
                        intVal(a.get("completion_tokens")),
                        str(a.get("error_code")), str(a.get("error_summary")),
                        str(a.get("created_by")));
            }
        }

        // Latest failed attempt
        TaskPlanAttemptView latestFailedAttempt = null;
        var failures = jdbc.queryForList(
                "SELECT * FROM ai_task_plan_attempt WHERE plan_id=? AND status='FAILED' ORDER BY finished_at DESC LIMIT 1",
                planId);
        if (!failures.isEmpty()) {
            var f = failures.getFirst();
            latestFailedAttempt = new TaskPlanAttemptView(
                    str(f.get("id")), str(f.get("stage")), str(f.get("status")),
                    str(f.get("provider")), str(f.get("model")),
                    str(f.get("started_at")), str(f.get("finished_at")),
                    longVal(f.get("latency_ms")), intVal(f.get("prompt_tokens")),
                    intVal(f.get("completion_tokens")),
                    str(f.get("error_code")), str(f.get("error_summary")),
                    str(f.get("created_by")));
        }

        // Latest attempt (any status)
        TaskPlanAttemptView latestAttempt = null;
        var latestAttempts = jdbc.queryForList(
                "SELECT * FROM ai_task_plan_attempt WHERE plan_id=? ORDER BY created_at DESC LIMIT 1",
                planId);
        if (!latestAttempts.isEmpty()) {
            var la = latestAttempts.getFirst();
            latestAttempt = new TaskPlanAttemptView(
                    str(la.get("id")), str(la.get("stage")), str(la.get("status")),
                    str(la.get("provider")), str(la.get("model")),
                    str(la.get("started_at")), str(la.get("finished_at")),
                    longVal(la.get("latency_ms")), intVal(la.get("prompt_tokens")),
                    intVal(la.get("completion_tokens")),
                    str(la.get("error_code")), str(la.get("error_summary")),
                    str(la.get("created_by")));
        }

        // Confirmation — nullable
        TaskPlanConfirmationView confirmation = null;
        var confirmations = jdbc.queryForList(
                "SELECT * FROM ai_task_plan_confirmation WHERE plan_id=? ORDER BY created_at DESC LIMIT 1",
                planId);
        if (!confirmations.isEmpty()) {
            var c = confirmations.getFirst();
            confirmation = new TaskPlanConfirmationView(
                    str(c.get("id")), str(c.get("status")),
                    jsonStrList(c.get("created_milestone_ids_json")),
                    jsonStrList(c.get("created_task_ids_json")),
                    intVal(c.get("created_dependency_count")),
                    str(c.get("created_at")), str(c.get("completed_at")));
        }

        // Latest version — nullable
        TaskPlanVersionView latestVersion = null;
        if (plan.latestVersionId() != null) {
            var versions = jdbc.queryForList(
                    "SELECT * FROM ai_task_plan_version WHERE id=?", plan.latestVersionId());
            if (!versions.isEmpty()) {
                var v = versions.getFirst();
                latestVersion = new TaskPlanVersionView(
                        str(v.get("id")), ((Number) v.get("version_no")).intValue(),
                        str(v.get("source_type")), str(v.get("based_on_version_id")),
                        str(v.get("created_by")), str(v.get("created_at")));
            }
        }

        // Validation from latest version
        TaskPlanValidationView validation = new TaskPlanValidationView(List.of(), List.of());
        if (plan.latestVersionId() != null) {
            var validations = jdbc.queryForList(
                    "SELECT validation_result_json FROM ai_task_plan_version WHERE id=?",
                    plan.latestVersionId());
            if (!validations.isEmpty() && validations.getFirst().get("validation_result_json") != null) {
                Object raw = validations.getFirst().get("validation_result_json");
                Map<String, Object> vr = parseValidationJson(raw.toString());
                if (vr != null) {
                    @SuppressWarnings("unchecked")
                    List<String> errors = (List<String>) vr.getOrDefault("errors", List.of());
                    @SuppressWarnings("unchecked")
                    List<String> warnings = (List<String>) vr.getOrDefault("warnings", List.of());
                    validation = new TaskPlanValidationView(errors, warnings);
                }
            }
        }

        // Task 11: Structured issues from issue repository
        List<StructuredValidationIssue> structuredIssues = List.of();
        if (plan.latestVersionId() != null) {
            structuredIssues = issueRepo.findByVersion(planId, plan.latestVersionId());
        }

        boolean readyWithIssues = plan.status().name().equals("READY_WITH_ISSUES");
        boolean canEdit = write && (ready || readyWithIssues);
        boolean canPartialRegenerate = write && (ready || readyWithIssues);
        boolean canConfirm = write && ready && structuredIssues.stream()
                .noneMatch(i -> i.severity() != com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity.WARNING);

        TaskPlanPermissions permissions = new TaskPlanPermissions(
                canEdit,
                write && plan.status().isGenerating(),
                write && plan.status().name().equals("DETAIL_GENERATION_FAILED"),
                write && List.of("READY", "READY_WITH_ISSUES", "FAILED", "DETAIL_GENERATION_FAILED", "CANCELED").contains(plan.status().name()),
                canConfirm,
                write && List.of("READY", "READY_WITH_ISSUES", "FAILED", "DETAIL_GENERATION_FAILED", "CANCELED").contains(plan.status().name()),
                write && (ready || readyWithIssues),
                canPartialRegenerate);

        return new TaskPlanDetailView(plan, latestVersion, activeAttempt, latestFailedAttempt,
                latestAttempt, confirmation, validation, permissions, structuredIssues);
    }

    public List<TaskPlanVersionRecord> versions(UUID projectId, UUID planId, UUID actor) {
        access.requireMember(projectId, actor); return repository.versions(projectId, planId);
    }

    public Map<String, Object> version(UUID projectId, UUID planId, UUID versionId, UUID actor) {
        access.requireMember(projectId, actor);
        TaskPlanVersionRecord version = repository.requireVersion(projectId, planId, versionId);
        return Map.of("version", version, "draft", repository.draft(version));
    }

    /** Task 11: Events API — returns recent plan events (max 100). */
    public List<TaskPlanEventRecord> events(UUID projectId, UUID planId, UUID actor) {
        access.requireMember(projectId, actor);
        repository.require(projectId, planId);
        return eventRepo.findByPlan(planId, 100);
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static Long longVal(Object v) { return v == null ? null : ((Number) v).longValue(); }
    private static Integer intVal(Object v) { return v == null ? null : ((Number) v).intValue(); }

    @SuppressWarnings("unchecked")
    private static List<String> jsonStrList(Object json) {
        if (json == null) return List.of();
        try {
            String s = json.toString();
            if (s.equals("[]") || s.equals("null")) return List.of();
            // Simple JSON array parse — extract string elements
            s = s.replaceAll("[\\[\\]\"\\s]", "");
            if (s.isEmpty()) return List.of();
            return java.util.Arrays.asList(s.split(","));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> parseValidationJson(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
