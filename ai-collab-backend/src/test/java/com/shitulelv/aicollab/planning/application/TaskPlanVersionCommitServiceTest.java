package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 7: Unit tests for TaskPlanVersionCommitService.
 * Verifies the commit flow delegates correctly to repositories.
 */
@ExtendWith(MockitoExtension.class)
class TaskPlanVersionCommitServiceTest {

    @Mock TaskPlanRepository repository;
    @Mock TaskPlanIssueRepository issueRepo;
    @Mock TaskPlanEventRepository eventRepo;

    private TaskPlanVersionCommitService service;

    @BeforeEach
    void setUp() {
        service = new TaskPlanVersionCommitService(repository, issueRepo, eventRepo);
    }

    @Test
    void commitWritesVersionIssuesAndEvent() {
        UUID planId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        TaskPlanRecord plan = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                null, null, 20, "[]", TaskPlanStatus.DETAIL_GENERATING,
                1, null, 1L, UUID.randomUUID(), actorId,
                null, null, null, null);

        TaskPlanVersionRecord versionRecord = new TaskPlanVersionRecord(
                versionId, planId, 2, "AI_COMPLETE", null, 1L,
                "summary", "[]", "[]", "[]", "[]", "[]", "{}", actorId, null);

        when(repository.appendGeneratedVersion(
                eq(projectId), eq(planId), anyLong(),
                any(UUID.class), any(TaskPlanStatus.class), anyString(),
                any(), any(TaskPlanDraft.class), any(UUID.class), any(ValidationResult.class)))
                .thenReturn(versionId);
        when(repository.requireVersion(eq(projectId), eq(planId), eq(versionId))).thenReturn(versionRecord);

        ValidationAssessment assessment = new ValidationAssessment(List.of(
                new StructuredValidationIssue("TASK_UNASSIGNED", ValidationIssueSeverity.WARNING,
                        "TASK", "T1", "assigneeId", null, Map.of())));

        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(), List.of(), List.of());

        TaskPlanVersionRecord result = service.commit(
                plan, draft, TaskPlanVersionSource.AI_COMPLETE,
                assessment, TaskPlanStatus.READY, "PLAN_GENERATED",
                actorId, null);

        assertNotNull(result);
        verify(repository).appendGeneratedVersion(
                eq(projectId), eq(planId), anyLong(),
                any(UUID.class), any(TaskPlanStatus.class), anyString(),
                any(), any(TaskPlanDraft.class), any(UUID.class), any(ValidationResult.class));
        verify(issueRepo).replaceForVersion(eq(planId), eq(versionId), any());
        verify(eventRepo).append(argThat(event ->
                event.planId().equals(planId)
                && event.eventType().equals("PLAN_GENERATED")
                && event.actorId().equals(actorId)));
    }

    @Test
    void commitWithNoIssuesSkipsIssueWrite() {
        UUID planId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        TaskPlanRecord plan = new TaskPlanRecord(planId, projectId, "title", "goal", "constraints",
                null, null, 20, "[]", TaskPlanStatus.DETAIL_GENERATING,
                1, null, 1L, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null);

        TaskPlanVersionRecord versionRecord = new TaskPlanVersionRecord(
                versionId, planId, 2, "AI_COMPLETE", null, 1L,
                "summary", "[]", "[]", "[]", "[]", "[]", "{}", UUID.randomUUID(), null);

        when(repository.appendGeneratedVersion(
                any(UUID.class), any(UUID.class), anyLong(),
                any(UUID.class), any(TaskPlanStatus.class), anyString(),
                any(), any(TaskPlanDraft.class), any(UUID.class), any(ValidationResult.class)))
                .thenReturn(versionId);
        when(repository.requireVersion(any(UUID.class), any(UUID.class), any(UUID.class))).thenReturn(versionRecord);

        ValidationAssessment assessment = ValidationAssessment.empty();
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(), List.of(), List.of());

        service.commit(plan, draft, TaskPlanVersionSource.AI_COMPLETE,
                assessment, TaskPlanStatus.READY, "PLAN_GENERATED",
                UUID.randomUUID(), null);

        verify(issueRepo, never()).replaceForVersion(any(), any(), any());
    }

    @Test
    void commitReturnsNullWhenVersionAppendFails() {
        UUID planId = UUID.randomUUID();
        TaskPlanRecord plan = new TaskPlanRecord(planId, UUID.randomUUID(), "title", "goal", "constraints",
                null, null, 20, "[]", TaskPlanStatus.DETAIL_GENERATING,
                1, null, 1L, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null);

        when(repository.appendGeneratedVersion(
                any(UUID.class), any(UUID.class), anyLong(),
                any(UUID.class), any(TaskPlanStatus.class), anyString(),
                any(), any(TaskPlanDraft.class), any(UUID.class), any(ValidationResult.class)))
                .thenReturn(null);

        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(), List.of(), List.of());

        TaskPlanVersionRecord result = service.commit(
                plan, draft, TaskPlanVersionSource.AI_COMPLETE,
                ValidationAssessment.empty(), TaskPlanStatus.READY,
                "PLAN_GENERATED", UUID.randomUUID(), null);

        assertNull(result);
        verify(issueRepo, never()).replaceForVersion(any(), any(), any());
        verify(eventRepo, never()).append(any());
    }

    @Test
    void countUnresolvedBlockingDelegatesToIssueRepo() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(issueRepo.countUnresolvedBlocking(planId, versionId)).thenReturn(3);

        int count = service.countUnresolvedBlocking(planId, versionId);
        assertEquals(3, count);
    }

    @Test
    void resolveIssuesDelegatesToIssueRepo() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        List<UUID> issueIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        service.resolveIssues(planId, versionId, issueIds, actorId);
        verify(issueRepo).markResolved(planId, versionId, issueIds, actorId);
    }
}
