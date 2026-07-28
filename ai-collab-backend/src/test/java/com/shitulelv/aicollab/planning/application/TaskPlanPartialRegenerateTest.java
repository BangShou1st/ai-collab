package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.api.PartialRegenerateRequest;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftNormalizer;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
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
    @Mock TaskPlanIssueRepository issueRepo;
    @Mock TaskPlanEventRepository eventRepo;
    @Mock TaskPlanPartialRepairService partialRepairService;

    private TaskPlanCommandService service;
    private TaskPlanVersionCommitService commitService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        commitService = new TaskPlanVersionCommitService(repository, issueRepo, eventRepo);
        service = new TaskPlanCommandService(access, repository, orchestrator, null, null,
                null, null, null, new TaskPlanDraftNormalizer(), new GenerationOutcomeDecider(),
                commitService, new TaskPlanRepairPatchParser(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new TaskPlanRepairPatchApplier(), null, null, partialRepairService);
    }

    private TaskPlanRecord readyPlan() {
        return new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.READY, 1, versionId, 1L, null, actorId,
                null, null, null, null);
    }

    private TaskPlanVersionRecord versionRecord() {
        return new TaskPlanVersionRecord(versionId, planId, 1, "AI_COMPLETE", null, 1L,
                "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null);
    }

    @Test
    void partialRegenerateRejectsWhenNotReady() {
        TaskPlanRecord confirmingPlan = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.CONFIRMING, 1, versionId, 1L, null, actorId,
                null, null, null, null);
        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, 1, List.of(), Set.of(), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);
        when(partialRepairService.start(projectId, planId, request, actorId))
                .thenThrow(new com.shitulelv.aicollab.common.exception.BusinessException(
                        com.shitulelv.aicollab.common.exception.ErrorCode.TASK_PLAN_STATE_CONFLICT));

        assertThrows(Exception.class,
                () -> service.partialRegenerate(projectId, planId, request, actorId));
    }

    @Test
    void partialRegenerateRejectsStaleVersion() {
        UUID staleVersion = UUID.randomUUID();
        PartialRegenerateRequest request = new PartialRegenerateRequest(
                staleVersion, 1, List.of("T1"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);
        when(partialRepairService.start(projectId, planId, request, actorId))
                .thenThrow(new com.shitulelv.aicollab.common.exception.BusinessException(
                        com.shitulelv.aicollab.common.exception.ErrorCode.PLAN_VERSION_CONFLICT));

        assertThrows(Exception.class,
                () -> service.partialRegenerate(projectId, planId, request, actorId));
    }

    @Test
    void partialRegenerateDelegatesToAsynchronousRepairService() {
        PartialRegenerateRequest request = new PartialRegenerateRequest(
                versionId, 1, List.of("T1"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);
        TaskPlanRecord repairing = new TaskPlanRecord(
                planId, projectId, "title", "goal", "constraints",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), 20, "[]",
                TaskPlanStatus.REPAIRING, 1, versionId, 1L, UUID.randomUUID(), actorId,
                null, null, null, null);
        when(partialRepairService.start(projectId, planId, request, actorId)).thenReturn(repairing);

        assertEquals(TaskPlanStatus.REPAIRING,
                service.partialRegenerate(projectId, planId, request, actorId).status());
        verify(partialRepairService).start(projectId, planId, request, actorId);
    }
}
