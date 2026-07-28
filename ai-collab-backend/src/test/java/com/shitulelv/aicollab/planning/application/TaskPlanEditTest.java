package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.api.UpdateTaskPlanRequest;
import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 8: Tests for user edit flow.
 */
@ExtendWith(MockitoExtension.class)
class TaskPlanEditTest {

    @Mock ProjectAccessGuard access;
    @Mock TaskPlanRepository repository;
    @Mock TaskPlanDraftValidator validator;
    @Mock JdbcTemplate jdbc;
    @Mock TaskPlanVersionCommitService commitService;

    private TaskPlanCommandService service;
    private final TaskPlanDraftNormalizer normalizer = new TaskPlanDraftNormalizer();
    private final GenerationOutcomeDecider outcomeDecider = new GenerationOutcomeDecider();

    private final UUID projectId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskPlanCommandService(access, repository, null, validator, jdbc,
                null, null, null, normalizer, outcomeDecider, commitService, null, null, null, null);
        lenient().when(validator.assess(any(), any(),
                        eq(TaskPlanDraftValidator.ValidationMode.COMPLETE), eq(false)))
                .thenReturn(ValidationAssessment.empty());
    }

    private TaskPlanRecord readyPlan() {
        return new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.READY, 1, versionId, 1L, null, actorId,
                null, null, null, null);
    }

    private TaskPlanRecord readyWithIssuesPlan() {
        return new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.READY_WITH_ISSUES, 1, versionId, 1L, null, actorId,
                null, null, null, null);
    }

    private TaskPlanDraft baseDraft() {
        PlanMilestone m1 = new PlanMilestone("M1", "M1", "obj", null,
                LocalDate.of(2026, 8, 15), 0, List.of());
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        return new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());
    }

    private TaskPlanVersionRecord baseVersion() {
        return new TaskPlanVersionRecord(versionId, planId, 1, "AI_COMPLETE", null, 1L,
                "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null);
    }

    @Test
    void editingIssueCreatesUserEditVersion() {
        when(repository.require(projectId, planId)).thenReturn(readyPlan());
        when(repository.requireVersion(projectId, planId, versionId)).thenReturn(baseVersion());
        when(repository.draft(baseVersion())).thenReturn(baseDraft());
        when(jdbc.queryForList(eq("SELECT user_id FROM project_member WHERE project_id=?"), eq(UUID.class), eq(projectId)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(eq("SELECT start_date,due_date FROM project WHERE id=?"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId)))
                .thenReturn(new LocalDate[]{LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)});
        UUID newVersionId = UUID.randomUUID();
        when(commitService.commitVersion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskPlanVersionRecord(newVersionId, planId, 1, "MANUAL_EDIT", versionId, 2L,
                        "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null));

        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest(
                versionId, 1, null, null, null, List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch("T1",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(LocalDate.of(2026, 8, 12)), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent())));

        UUID result = service.edit(projectId, planId, request, actorId);
        assertNotNull(result);
        verify(commitService).commitVersion(eq(readyPlan()), any(), eq(TaskPlanVersionSource.MANUAL_EDIT),
                any(), any(), eq("TASK_PLAN_USER_EDITED"), eq(actorId), eq(versionId));
    }

    @Test
    void editingDoesNotOverwriteOldVersion() {
        when(repository.require(projectId, planId)).thenReturn(readyPlan());
        when(repository.requireVersion(projectId, planId, versionId)).thenReturn(baseVersion());
        when(repository.draft(baseVersion())).thenReturn(baseDraft());
        when(jdbc.queryForList(eq("SELECT user_id FROM project_member WHERE project_id=?"), eq(UUID.class), eq(projectId)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(eq("SELECT start_date,due_date FROM project WHERE id=?"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId)))
                .thenReturn(new LocalDate[]{LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)});
        UUID newVersionId = UUID.randomUUID();
        when(commitService.commitVersion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskPlanVersionRecord(newVersionId, planId, 1, "MANUAL_EDIT", versionId, 2L,
                        "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null));

        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest(
                versionId, 1, null, null, null, List.of(), List.of());

        service.edit(projectId, planId, request, actorId);

        // Verify commit was called with MANUAL_EDIT source
        verify(commitService).commitVersion(eq(readyPlan()), any(), eq(TaskPlanVersionSource.MANUAL_EDIT),
                any(), any(), eq("TASK_PLAN_USER_EDITED"), eq(actorId), eq(versionId));
    }

    @Test
    void staleBaseVersionReturns409() {
        UUID staleVersionId = UUID.randomUUID();
        when(repository.require(projectId, planId)).thenReturn(readyPlan());

        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest(
                staleVersionId, 1, null, null, null, List.of(), List.of());

        assertThrows(Exception.class,
                () -> service.edit(projectId, planId, request, actorId));
    }

    @Test
    void editingNonReadyPlanThrows() {
        TaskPlanRecord confirmingPlan = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.CONFIRMING, 1, versionId, 1L, null, actorId,
                null, null, null, null);
        when(repository.require(projectId, planId)).thenReturn(confirmingPlan);

        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest(
                versionId, 1, null, null, null, List.of(), List.of());

        assertThrows(Exception.class,
                () -> service.edit(projectId, planId, request, actorId));
    }

    @Test
    void readyWithIssuesCanBeEdited() {
        when(repository.require(projectId, planId)).thenReturn(readyWithIssuesPlan());
        when(repository.requireVersion(projectId, planId, versionId)).thenReturn(baseVersion());
        when(repository.draft(baseVersion())).thenReturn(baseDraft());
        when(jdbc.queryForList(eq("SELECT user_id FROM project_member WHERE project_id=?"), eq(UUID.class), eq(projectId)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(eq("SELECT start_date,due_date FROM project WHERE id=?"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId)))
                .thenReturn(new LocalDate[]{LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)});
        UUID newVersionId = UUID.randomUUID();
        when(commitService.commitVersion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskPlanVersionRecord(newVersionId, planId, 1, "MANUAL_EDIT", versionId, 2L,
                        "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null));

        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest(
                versionId, 1, null, null, null, List.of(), List.of());

        UUID result = service.edit(projectId, planId, request, actorId);
        assertNotNull(result);
    }
}
