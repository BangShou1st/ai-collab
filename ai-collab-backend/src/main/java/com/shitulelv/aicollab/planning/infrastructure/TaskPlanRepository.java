package com.shitulelv.aicollab.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.domain.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Repository
public class TaskPlanRepository {
    public record PartialRepairStart(TaskPlanRecord plan, UUID attemptId, TaskPlanStatus previousStatus) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TaskPlanRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public TaskPlanRecord create(UUID projectId, UUID actor, CreateTaskPlanRequest request) {
        UUID planId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        // Step 1: Insert plan without active_attempt_id to satisfy
        // fk_ai_task_plan_active_attempt (the attempt row does not exist yet).
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,constraints,plan_start_date,plan_due_date,
                  max_task_count,selected_document_ids_json,status,created_by)
                VALUES (?,?,?,?,?,?,?, ?,?::jsonb,'SKELETON_GENERATING',?)
                """, planId, projectId, request.title().strip(), request.goal().strip(),
                request.constraints() == null ? "" : request.constraints(),
                request.planStartDate(), request.planDueDate(), request.maxTaskCount(),
                write(request.documentIds() == null ? List.of() : request.documentIds()), actor);
        // Step 2: Insert attempt — plan already exists, so attempt.plan_id FK is satisfied.
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,1,1,'SKELETON','QUEUED',?)
                """, attemptId, planId, actor);
        // Step 3: Bind attempt as active — attempt now exists, so active_attempt_id FK is satisfied.
        int updated = jdbc.update("""
                UPDATE ai_task_plan SET active_attempt_id=?, updated_at=now()
                WHERE id=? AND project_id=? AND active_attempt_id IS NULL
                  AND generation_seq=1 AND status='SKELETON_GENERATING'
                """, attemptId, planId, projectId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to bind active attempt: plan " + planId + " update affected " + updated + " rows");
        }
        return require(projectId, planId);
    }

    public List<TaskPlanRecord> list(UUID projectId, String status, int limit, int offset) {
        String sql = "SELECT * FROM ai_task_plan WHERE project_id=?"
                + (status == null || status.isBlank() ? "" : " AND status=?")
                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        Object[] args = status == null || status.isBlank()
                ? new Object[]{projectId, limit, offset} : new Object[]{projectId, status, limit, offset};
        return jdbc.query(sql, this::plan, args);
    }

    public TaskPlanRecord require(UUID projectId, UUID planId) {
        return jdbc.query("SELECT * FROM ai_task_plan WHERE project_id=? AND id=?", this::plan, projectId, planId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND));
    }

    public TaskPlanVersionRecord requireVersion(UUID projectId, UUID planId, UUID versionId) {
        return jdbc.query("""
                SELECT v.* FROM ai_task_plan_version v JOIN ai_task_plan p ON p.id=v.plan_id
                WHERE p.project_id=? AND p.id=? AND v.id=?
                """, this::version, projectId, planId, versionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_PLAN_VERSION_NOT_FOUND));
    }

    public List<TaskPlanVersionRecord> versions(UUID projectId, UUID planId) {
        require(projectId, planId);
        return jdbc.query("SELECT * FROM ai_task_plan_version WHERE plan_id=? ORDER BY version_no DESC",
                this::version, planId);
    }

    @Transactional
    public UUID appendVersion(UUID projectId, UUID planId, UUID expectedBase, String type,
                              UUID basedOn, TaskPlanDraft draft, UUID actor, ValidationResult validation,
                              TaskPlanStatus finalStatus) {
        TaskPlanRecord plan = lock(projectId, planId);
        // Interactive operations (MANUAL_EDIT, RESTORED, AI_PARTIAL_REPAIR) require READY or READY_WITH_ISSUES.
        // Async generation types (AI_*) are guarded by appendGeneratedVersion's attempt/seq checks.
        if (!type.startsWith("AI_") && plan.status() != TaskPlanStatus.READY
                && plan.status() != TaskPlanStatus.READY_WITH_ISSUES) stateConflict();
        if (expectedBase != null && !expectedBase.equals(plan.latestVersionId())) {
            throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
        }
        UUID id = UUID.randomUUID();
        int next = plan.latestVersionNo() + 1;
        String validationJson = validation != null
                ? write(Map.of("errors", validation.errorCodes(), "warnings", validation.warningCodes()))
                : "{\"errors\":[],\"warnings\":[]}";
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,based_on_version_id,
                  generation_seq,summary,assumptions_json,risks_json,milestones_json,tasks_json,sources_json,
                  validation_result_json,created_by)
                VALUES (?,?,?,?,?, ?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,
                  ?::jsonb,?)
                """, id, planId, next, type, basedOn, plan.generationSeq(), draft.summary(),
                write(draft.assumptions()), write(draft.risks()), write(draft.milestones()),
                write(draft.tasks()), write(draft.sources()), validationJson, actor);
        jdbc.update("""
                UPDATE ai_task_plan SET latest_version_no=?,latest_version_id=?,updated_at=now(),
                  status=CASE WHEN ?='AI_SKELETON' THEN 'DETAIL_GENERATING'
                              ELSE ? END,
                  active_attempt_id=CASE WHEN ?='AI_SKELETON' THEN active_attempt_id
                                          ELSE NULL END
                WHERE id=?
                """, next, id, type, finalStatus.name(), type, planId);
        return id;
    }

    @Transactional
    public UUID appendGeneratedVersion(UUID projectId, UUID planId, long generationSeq, UUID attemptId,
                                       TaskPlanStatus expectedStatus, String type, UUID basedOn,
                                       TaskPlanDraft draft, UUID actor, ValidationResult validation,
                                       TaskPlanStatus finalStatus) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (plan.generationSeq() != generationSeq || !attemptId.equals(plan.activeAttemptId())
                || plan.status() != expectedStatus) {
            finishAttempt(attemptId, "DISCARDED", "PLAN_GENERATION_CANCELED");
            return null;
        }
        if (basedOn != null && !basedOn.equals(plan.latestVersionId())) {
            finishAttempt(attemptId, "DISCARDED", "PLAN_VERSION_CONFLICT");
            return null;
        }
        return appendVersion(projectId, planId, basedOn, type, basedOn, draft, actor, validation, finalStatus);
    }

    /**
     * C4: Cancel returns the actual canceled attemptId from the transaction,
     * so the caller cancels the correct Future (not a stale pre-transaction read).
     */
    @Transactional
    public TaskPlanRecord cancel(UUID projectId, UUID planId) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (plan.status() == TaskPlanStatus.CANCELED) return plan;
        if (!plan.status().isGenerating()) stateConflict();
        jdbc.update("UPDATE ai_task_plan_attempt SET cancel_requested=true,status='CANCELED',updated_at=now() WHERE id=?",
                plan.activeAttemptId());
        jdbc.update("""
                UPDATE ai_task_plan SET status='CANCELED',generation_seq=generation_seq+1,
                  active_attempt_id=NULL,canceled_at=now(),updated_at=now() WHERE id=?
                """, planId);
        return require(projectId, planId);
    }

    /** C4: Returns the actual attemptId that was canceled in the transaction. */
    @Transactional
    public UUID cancelAndReturnAttemptId(UUID projectId, UUID planId) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (plan.status() == TaskPlanStatus.CANCELED) return null;
        if (!plan.status().isGenerating()) stateConflict();
        UUID actualAttemptId = plan.activeAttemptId();
        jdbc.update("UPDATE ai_task_plan_attempt SET cancel_requested=true,status='CANCELED',updated_at=now() WHERE id=?",
                actualAttemptId);
        jdbc.update("""
                UPDATE ai_task_plan SET status='CANCELED',generation_seq=generation_seq+1,
                  active_attempt_id=NULL,canceled_at=now(),updated_at=now() WHERE id=?
                """, planId);
        return actualAttemptId;
    }

    @Transactional
    public TaskPlanRecord startGeneration(UUID projectId, UUID planId, UUID actor, boolean detailOnly) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (detailOnly && plan.status() != TaskPlanStatus.DETAIL_GENERATION_FAILED) stateConflict();
        if (!detailOnly && !List.of(TaskPlanStatus.READY, TaskPlanStatus.READY_WITH_ISSUES, TaskPlanStatus.FAILED,
                TaskPlanStatus.DETAIL_GENERATION_FAILED, TaskPlanStatus.CANCELED).contains(plan.status())) stateConflict();
        long sequence = detailOnly ? plan.generationSeq() : plan.generationSeq() + 1;
        UUID attempt = UUID.randomUUID();
        Integer no = jdbc.queryForObject("SELECT coalesce(max(attempt_no),0)+1 FROM ai_task_plan_attempt WHERE plan_id=?",
                Integer.class, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,?,?,?,'QUEUED',?)
                """, attempt, planId, no, sequence, detailOnly ? "DETAIL" : "SKELETON", actor);
        jdbc.update("""
                UPDATE ai_task_plan SET status=?,generation_seq=?,active_attempt_id=?,canceled_at=NULL,
                  last_error_code=NULL,last_error_summary=NULL,updated_at=now() WHERE id=?
                """, detailOnly ? "DETAIL_GENERATING" : "SKELETON_GENERATING", sequence, attempt, planId);
        return require(projectId, planId);
    }

    public boolean active(UUID planId, long sequence, UUID attempt, TaskPlanStatus expected) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_task_plan p JOIN ai_task_plan_attempt a ON a.id=p.active_attempt_id
                WHERE p.id=? AND p.generation_seq=? AND p.active_attempt_id=? AND p.status=?
                  AND a.cancel_requested=false
                """, Integer.class, planId, sequence, attempt, expected.name());
        return count != null && count == 1;
    }

    @Transactional
    public UUID startDetailAfterSkeleton(UUID projectId, UUID planId, UUID actor) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (plan.status() != TaskPlanStatus.DETAIL_GENERATING) stateConflict();
        UUID attempt = UUID.randomUUID();
        Integer no = jdbc.queryForObject("SELECT coalesce(max(attempt_no),0)+1 FROM ai_task_plan_attempt WHERE plan_id=?",
                Integer.class, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,?,?, 'DETAIL','QUEUED',?)
                """, attempt, planId, no, plan.generationSeq(), actor);
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id=?,updated_at=now() WHERE id=?", attempt, planId);
        return attempt;
    }

    @Transactional
    /**
     * H3: Atomic CAS — matches attempt AND plan state in a single UPDATE.
     * Prevents TOCTOU between active() and markRunning().
     */
    public boolean markRunning(UUID attemptId, UUID planId, long generationSeq, TaskPlanStatus expectedPlanStatus) {
        int updated = jdbc.update("""
                UPDATE ai_task_plan_attempt SET status='RUNNING',started_at=now(),updated_at=now()
                WHERE id=? AND status='QUEUED' AND cancel_requested=false
                  AND plan_id=? AND generation_seq=? AND EXISTS (
                    SELECT 1 FROM ai_task_plan p
                    WHERE p.id=ai_task_plan_attempt.plan_id
                      AND p.generation_seq=ai_task_plan_attempt.generation_seq
                      AND p.active_attempt_id=ai_task_plan_attempt.id
                      AND p.status=?
                  )
                """, attemptId, planId, generationSeq, expectedPlanStatus.name());
        return updated == 1;
    }

    @Transactional
    public UUID startRepair(UUID planId, long generationSeq, UUID parentAttemptId,
                            TaskPlanStatus expectedStatus, UUID actor) {
        Map<String, Object> plan = jdbc.queryForMap(
                "SELECT generation_seq,active_attempt_id,status FROM ai_task_plan WHERE id=? FOR UPDATE", planId);
        if (((Number) plan.get("generation_seq")).longValue() != generationSeq
                || !parentAttemptId.equals(plan.get("active_attempt_id"))
                || !expectedStatus.name().equals(plan.get("status"))) return null;
        finishAttempt(parentAttemptId, "FAILED", "PLANNING_MODEL_INVALID_OUTPUT");
        UUID repair = UUID.randomUUID();
        Integer no = jdbc.queryForObject("SELECT coalesce(max(attempt_no),0)+1 FROM ai_task_plan_attempt WHERE plan_id=?",
                Integer.class, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,parent_attempt_id,attempt_no,generation_seq,
                  stage,status,repair_count,created_by,started_at)
                VALUES (?,?,?,?,?,'REPAIR','RUNNING',1,?,now())
                """, repair, planId, parentAttemptId, no, generationSeq, actor);
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id=?,updated_at=now() WHERE id=?", repair, planId);
        return repair;
    }

    @Transactional
    public PartialRepairStart startPartialRepair(UUID projectId, UUID planId, UUID baseVersionId,
                                                  int expectedVersionNo, UUID actor) {
        TaskPlanRecord plan = lock(projectId, planId);
        if (plan.status() != TaskPlanStatus.READY && plan.status() != TaskPlanStatus.READY_WITH_ISSUES) {
            stateConflict();
        }
        if (!baseVersionId.equals(plan.latestVersionId()) || expectedVersionNo != plan.latestVersionNo()) {
            throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
        }
        UUID attempt = UUID.randomUUID();
        Integer no = jdbc.queryForObject(
                "SELECT coalesce(max(attempt_no),0)+1 FROM ai_task_plan_attempt WHERE plan_id=?",
                Integer.class, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,?,?, 'REPAIR','QUEUED',?)
                """, attempt, planId, no, plan.generationSeq(), actor);
        int updated = jdbc.update("""
                UPDATE ai_task_plan SET status='REPAIRING',active_attempt_id=?,updated_at=now()
                WHERE id=? AND project_id=? AND latest_version_id=? AND latest_version_no=?
                  AND status IN ('READY','READY_WITH_ISSUES')
                """, attempt, planId, projectId, baseVersionId, expectedVersionNo);
        if (updated != 1) throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT);
        return new PartialRepairStart(require(projectId, planId), attempt, plan.status());
    }

    @Transactional
    public void failPartialRepair(UUID projectId, UUID planId, UUID attemptId,
                                  TaskPlanStatus previousStatus, String errorCode) {
        TaskPlanRecord plan = lock(projectId, planId);
        finishAttempt(attemptId, "FAILED", errorCode);
        if (plan.status() == TaskPlanStatus.REPAIRING && attemptId.equals(plan.activeAttemptId())) {
            jdbc.update("""
                    UPDATE ai_task_plan SET status=?,active_attempt_id=NULL,
                      last_error_code=?,last_error_summary='局部修复未通过安全校验',updated_at=now()
                    WHERE id=? AND project_id=? AND status='REPAIRING' AND active_attempt_id=?
                    """, previousStatus.name(), errorCode, planId, projectId, attemptId);
        }
    }

    @Transactional
    public void finishAttempt(UUID attemptId, String status, String errorCode) {
        finishAttempt(attemptId, status, errorCode, null, null, null, null, null, null);
    }

    /**
     * P2-1: Persist full attempt metrics — provider, model, timing, tokens, error summary.
     * Does not store API key, prompt, or raw model response.
     */
    @Transactional
    public void finishAttempt(UUID attemptId, String status, String errorCode,
                              String provider, String model, Long latencyMs,
                              Integer promptTokens, Integer completionTokens,
                              String errorSummary) {
        jdbc.update("""
                UPDATE ai_task_plan_attempt SET status=?,error_code=?,error_summary=?,
                  provider=COALESCE(?,provider), model=COALESCE(?,model),
                  latency_ms=COALESCE(?,latency_ms),
                  prompt_tokens=COALESCE(?,prompt_tokens),
                  completion_tokens=COALESCE(?,completion_tokens),
                  finished_at=now(),updated_at=now()
                WHERE id=?
                """, status, errorCode, errorSummary,
                provider, model, latencyMs, promptTokens, completionTokens, attemptId);
    }

    @Transactional
    public void fail(UUID planId, long generationSeq, UUID attemptId,
                     TaskPlanStatus expectedStatus, TaskPlanStatus status, String code) {
        fail(planId, generationSeq, attemptId, expectedStatus, status, code, "模型输出未通过安全校验");
    }

    @Transactional
    public void fail(UUID planId, long generationSeq, UUID attemptId,
                     TaskPlanStatus expectedStatus, TaskPlanStatus status, String code, String errorSummary) {
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT generation_seq,active_attempt_id,status FROM ai_task_plan WHERE id=? FOR UPDATE", planId);
        boolean active = ((Number) current.get("generation_seq")).longValue() == generationSeq
                && attemptId.equals(current.get("active_attempt_id"))
                && expectedStatus.name().equals(current.get("status"));
        finishAttempt(attemptId, active ? "FAILED" : "DISCARDED",
                active ? code : "PLAN_GENERATION_CANCELED");
        if (!active) return;
        // H1: Clear active_attempt_id for terminal states (FAILED, DETAIL_GENERATION_FAILED)
        jdbc.update("""
                UPDATE ai_task_plan SET status=?,active_attempt_id=NULL,
                  last_error_code=?,last_error_summary=?,updated_at=now()
                WHERE id=? AND generation_seq=? AND active_attempt_id=? AND status=?
                """, status.name(), code, errorSummary, planId,
                generationSeq, attemptId, expectedStatus.name());
    }

    public TaskPlanRecord lock(UUID projectId, UUID planId) {
        return jdbc.query("SELECT * FROM ai_task_plan WHERE project_id=? AND id=? FOR UPDATE",
                this::plan, projectId, planId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND));
    }

    public TaskPlanDraft draft(TaskPlanVersionRecord v) {
        try {
            return new TaskPlanDraft(v.summary(),
                    json.readValue(v.assumptionsJson(), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    json.readValue(v.risksJson(), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    json.readValue(v.milestonesJson(), json.getTypeFactory().constructCollectionType(List.class,
                            com.shitulelv.aicollab.planning.domain.PlanMilestone.class)),
                    json.readValue(v.tasksJson(), json.getTypeFactory().constructCollectionType(List.class,
                            com.shitulelv.aicollab.planning.domain.PlanTask.class)),
                    json.readValue(v.sourcesJson(), json.getTypeFactory().constructCollectionType(List.class,
                            com.shitulelv.aicollab.planning.domain.PlanSource.class)));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT);
        }
    }

    /**
     * C9: Lock project_member rows for the given user IDs to prevent removal during save/confirm.
     * Uses a single parameterized query with FOR SHARE to prevent 500 errors when members are missing.
     * Returns the actual locked member IDs for validation.
     */
    @Transactional
    public Set<UUID> lockProjectMembers(UUID projectId, Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Set.of();

        // Build parameterized IN clause
        String placeholders = userIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = "SELECT user_id FROM project_member WHERE project_id=? AND user_id IN (" + placeholders + ") ORDER BY user_id FOR SHARE";

        // Build parameter array
        Object[] params = new Object[userIds.size() + 1];
        params[0] = projectId;
        int i = 1;
        for (UUID userId : userIds) {
            params[i++] = userId;
        }

        Set<UUID> lockedMembers = new HashSet<>(jdbc.queryForList(sql, UUID.class, params));

        // Validate all requested members were found
        if (lockedMembers.size() != userIds.size()) {
            Set<UUID> missing = new HashSet<>(userIds);
            missing.removeAll(lockedMembers);
            throw new BusinessException(ErrorCode.TASK_ASSIGNEE_NOT_MEMBER,
                    "以下成员不是项目成员或已退出项目: " + missing);
        }

        return lockedMembers;
    }

    public ValidationContext validationContext(TaskPlanRecord plan) {
        java.time.LocalDate[] projectDates = jdbc.queryForObject(
                "SELECT start_date,due_date FROM project WHERE id=?",
                (rs, row) -> new java.time.LocalDate[]{
                        rs.getObject(1, java.time.LocalDate.class),
                        rs.getObject(2, java.time.LocalDate.class)}, plan.projectId());
        Set<UUID> members = new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM project_member WHERE project_id=?", UUID.class, plan.projectId()));
        Set<String> titles = new HashSet<>(jdbc.queryForList("""
                SELECT lower(title) FROM project_task WHERE project_id=?
                UNION SELECT lower(name) FROM milestone WHERE project_id=?
                """, String.class, plan.projectId(), plan.projectId()));
        return new ValidationContext(projectDates[0], projectDates[1], plan.planStartDate(),
                plan.planDueDate(), plan.maxTaskCount(), members, titles);
    }

    private TaskPlanRecord plan(ResultSet r, int n) throws SQLException {
        return new TaskPlanRecord(r.getObject("id", UUID.class), r.getObject("project_id", UUID.class),
                r.getString("title"), r.getString("goal"), r.getString("constraints"),
                r.getObject("plan_start_date", java.time.LocalDate.class),
                r.getObject("plan_due_date", java.time.LocalDate.class), r.getInt("max_task_count"),
                r.getString("selected_document_ids_json"), TaskPlanStatus.valueOf(r.getString("status")),
                r.getInt("latest_version_no"), r.getObject("latest_version_id", UUID.class),
                r.getLong("generation_seq"), r.getObject("active_attempt_id", UUID.class),
                r.getObject("created_by", UUID.class), r.getString("last_error_code"),
                r.getString("last_error_summary"), r.getObject("created_at", java.time.OffsetDateTime.class),
                r.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private TaskPlanVersionRecord version(ResultSet r, int n) throws SQLException {
        return new TaskPlanVersionRecord(r.getObject("id", UUID.class), r.getObject("plan_id", UUID.class),
                r.getInt("version_no"), r.getString("source_type"), r.getObject("based_on_version_id", UUID.class),
                r.getLong("generation_seq"), r.getString("summary"), r.getString("assumptions_json"),
                r.getString("risks_json"), r.getString("milestones_json"), r.getString("tasks_json"),
                r.getString("sources_json"), r.getString("validation_result_json"),
                r.getObject("created_by", UUID.class), r.getObject("created_at", java.time.OffsetDateTime.class));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("JSON serialization failed", exception); }
    }

    private static void stateConflict() { throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT); }
}
