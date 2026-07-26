package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.api.SaveTaskPlanVersionRequest;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
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

    public TaskPlanCommandService(ProjectAccessGuard access, TaskPlanRepository repository,
                                  TaskPlanGenerationOrchestrator orchestrator,
                                  TaskPlanDraftValidator validator, JdbcTemplate jdbc) {
        this.access = access; this.repository = repository; this.orchestrator = orchestrator;
        this.validator = validator; this.jdbc = jdbc;
    }

    public TaskPlanRecord create(UUID projectId, CreateTaskPlanRequest request, UUID actor) {
        access.requireAdmin(projectId, actor);
        validateCreate(projectId, request);
        TaskPlanRecord plan = repository.create(projectId, actor, request);
        orchestrator.dispatch(plan, false);
        return plan;
    }

    public TaskPlanRecord cancel(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        return repository.cancel(projectId, planId);
    }

    public TaskPlanRecord retryDetail(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.startGeneration(projectId, planId, actor, true);
        orchestrator.dispatch(plan, true);
        return plan;
    }

    public TaskPlanRecord regenerate(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.startGeneration(projectId, planId, actor, false);
        orchestrator.dispatch(plan, false);
        return plan;
    }

    public UUID save(UUID projectId, UUID planId, SaveTaskPlanVersionRequest request, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        ensureValid(projectId, plan, request.draft());
        return repository.appendVersion(projectId, planId, request.baseVersionId(),
                "MANUAL_EDIT", request.baseVersionId(), request.draft(), actor);
    }

    public UUID restore(UUID projectId, UUID planId, UUID versionId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.require(projectId, planId);
        TaskPlanVersionRecord source = repository.requireVersion(projectId, planId, versionId);
        TaskPlanDraft draft = repository.draft(source);
        ensureValid(projectId, plan, draft);
        return repository.appendVersion(projectId, planId, plan.latestVersionId(),
                "RESTORED", versionId, draft, actor);
    }

    @Transactional
    public void delete(UUID projectId, UUID planId, UUID actor) {
        access.requireAdmin(projectId, actor);
        TaskPlanRecord plan = repository.lock(projectId, planId);
        if (!List.of("READY", "FAILED", "DETAIL_GENERATION_FAILED", "CANCELED")
                .contains(plan.status().name())) throw new BusinessException(ErrorCode.TASK_PLAN_STATE_CONFLICT);
        jdbc.update("DELETE FROM ai_task_plan WHERE id=?", planId);
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

    private void ensureValid(UUID projectId, TaskPlanRecord plan, TaskPlanDraft draft) {
        Set<UUID> members = new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM project_member WHERE project_id=?", UUID.class, projectId));
        LocalDate[] projectDates = jdbc.queryForObject("SELECT start_date,due_date FROM project WHERE id=?",
                (rs, row) -> new LocalDate[]{rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, projectId);
        var result = validator.validate(new ValidationContext(projectDates[0], projectDates[1],
                plan.planStartDate(), plan.planDueDate(), plan.maxTaskCount(), members, Set.of()), draft);
        if (!result.valid()) throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED,
                "规划校验失败：" + String.join(",", result.errorCodes()));
    }

    private static Object[] concat(UUID projectId, List<UUID> ids) {
        Object[] values = new Object[ids.size() + 1]; values[0] = projectId;
        for (int i = 0; i < ids.size(); i++) values[i + 1] = ids.get(i);
        return values;
    }
}
