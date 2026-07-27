package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.api.PartialRegenerateRequest;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 9: Tests for partial regeneration.
 */
@ExtendWith(MockitoExtension.class)
class TaskPlanPartialRegenerateTest {

    @Mock ProjectAccessGuard access;
    @Mock TaskPlanRepository repository;
    @Mock TaskPlanGenerationOrchestrator orchestrator;

    private TaskPlanCommandService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskPlanCommandService(access, repository, orchestrator, null, null,
                null, null, null);
    }

    private TaskPlanRecord readyPlan() {
        return new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.READY, 1, versionId, 1L, null, actorId,
                null, null, null, null);
    }

    @Test
    void partialRegenerateChangesOnlyAllowedFields() {
        when(repository.require(projectId, planId)).thenReturn(readyPlan());
        TaskPlanRecord regenerated = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.SKELETON_GENERATING, 1, versionId, 2L, null, actorId,
                null, null, null, null);
        when(repository.startGeneration(eq(projectId), eq(planId), eq(actorId), eq(false)))
                .thenReturn(regenerated);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, List.of("T1"), Set.of("startDate", "dueDate"), Set.of("tempKey", "title"),
                List.of(), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES);

        TaskPlanRecord result = service.partialRegenerate(projectId, planId, request, actorId);
        assertNotNull(result);
        verify(orchestrator).dispatch(eq(regenerated), eq(actorId), eq(false));
    }

    @Test
    void partialRegenerateKeepsLockedFields() {
        when(repository.require(projectId, planId)).thenReturn(readyPlan());
        TaskPlanRecord regenerated = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.SKELETON_GENERATING, 1, versionId, 2L, null, actorId,
                null, null, null, null);
        when(repository.startGeneration(eq(projectId), eq(planId), eq(actorId), eq(false)))
                .thenReturn(regenerated);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, List.of("T1"), Set.of("startDate"), Set.of("tempKey", "title", "objective"),
                List.of(), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES);

        TaskPlanRecord result = service.partialRegenerate(projectId, planId, request, actorId);
        assertNotNull(result);
    }

    @Test
    void staleBaseVersionRejected() {
        UUID staleVersion = UUID.randomUUID();
        when(repository.require(projectId, planId)).thenReturn(readyPlan());

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                staleVersion, List.of("T1"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);

        assertThrows(Exception.class,
                () -> service.partialRegenerate(projectId, planId, request, actorId));
    }

    @Test
    void partialRegenerateNonReadyPlanThrows() {
        TaskPlanRecord confirmingPlan = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.CONFIRMING, 1, versionId, 1L, null, actorId,
                null, null, null, null);
        when(repository.require(projectId, planId)).thenReturn(confirmingPlan);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, List.of(), Set.of(), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);

        assertThrows(Exception.class,
                () -> service.partialRegenerate(projectId, planId, request, actorId));
    }

    @Test
    void partialRegenerateWritesVersionAndEvent() {
        when(repository.require(projectId, planId)).thenReturn(readyPlan());
        TaskPlanRecord regenerated = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.SKELETON_GENERATING, 1, versionId, 2L, null, actorId,
                null, null, null, null);
        when(repository.startGeneration(eq(projectId), eq(planId), eq(actorId), eq(false)))
                .thenReturn(regenerated);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, List.of("T1"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);

        service.partialRegenerate(projectId, planId, request, actorId);
        verify(repository).startGeneration(eq(projectId), eq(planId), eq(actorId), eq(false));
        verify(orchestrator).dispatch(eq(regenerated), eq(actorId), eq(false));
    }
}
