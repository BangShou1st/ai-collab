package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlanningDraftRouterTest {

    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ATTEMPT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String VALID_PLAN = """
            {"milestones":[{"tempKey":"m1","title":"阶段一","objective":"完成阶段目标",
              "description":"阶段说明","targetDate":"2026-12-01","sourceRefs":[],"sortOrder":0}],
             "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"任务一",
               "objective":"完成任务目标","description":"任务说明","priority":"HIGH",
               "estimatedHours":4.0,"startDate":"2026-11-20","dueDate":"2026-11-30",
               "assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0}]}
            """;
    private static final String TWO_TASK_PLAN = """
            {"milestones":[{"tempKey":"m1","title":"阶段一","objective":"完成阶段目标",
              "description":"阶段说明","targetDate":"2026-12-01","sourceRefs":[],"sortOrder":0}],
             "tasks":[
               {"tempKey":"t1","milestoneTempKey":"m1","title":"任务一",
                "objective":"完成任务目标","description":"任务说明","priority":"HIGH",
                "estimatedHours":4.0,"startDate":"2026-11-20","dueDate":"2026-11-30",
                "assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0},
               {"tempKey":"t2","milestoneTempKey":"m1","title":"任务二",
                "objective":"完成任务目标二","description":"任务说明二","priority":"MEDIUM",
                "estimatedHours":4.0,"startDate":"2026-11-20","dueDate":"2026-11-30",
                "assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":1}]}
            """;
    private static final String UNKNOWN_FIELD_PLAN = """
            {"milestones":[{"tempKey":"m1","title":"阶段一","objective":"完成阶段目标",
              "description":"阶段说明","targetDate":"2026-12-01","sourceRefs":[],"sortOrder":0}],
             "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"任务一",
               "objective":"完成任务目标","description":"任务说明","priority":"HIGH",
               "estimatedHours":4.0,"startDate":"2026-11-20","dueDate":"2026-11-30",
               "assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0,
               "unexpectedField":"not-in-contract"}]}
            """;

    @Test
    void validPythonSkeletonIsHandledOnlyAfterJavaValidator() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftProperties properties = properties(PlanningDraftMode.PYTHON);
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, VALID_PLAN));

        PlanningDraftRouter router = new PlanningDraftRouter(properties, gateway, snapshots,
                repository, new TaskPlanDraftValidator(), new SimpleMeterRegistry());
        PlanningDraftRouter.Route route = router.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isTrue();
        assertThat(route.canceled()).isFalse();
        assertThat(route.marker()).isEqualTo("python:SKELETON");
        assertThat(route.draft()).isNotNull();
        assertThat(route.draft().tasks()).hasSize(1);
        assertThat(route.draft().tasks().getFirst().description()).isNull();
        assertThat(route.draft().tasks().getFirst().priority()).isNull();
        assertThat(route.draft().tasks().getFirst().dependencyTempKeys()).isEmpty();
        assertThat(route.metrics().provider()).isEqualTo(PlanningDraftRouter.PROVIDER);
        assertThat(route.metrics().latencyMs()).isGreaterThanOrEqualTo(0);
        verify(snapshots).buildRequest(any(), any(), anyInt());
        verify(repository).appendAttemptDiagnostic(eq(PLAN_ID), eq(ATTEMPT_ID),
                org.mockito.ArgumentMatchers.argThat(entry ->
                        "PLANNING_DRAFT_ROUTE".equals(entry.get("kind"))
                                && "handled".equals(entry.get("outcome"))));
    }

    @Test
    void javaModeIsZeroCostAndDoesNotBuildSnapshot() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.JAVA),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.canceled()).isFalse();
        verifyNoInteractions(gateway, snapshots, repository);
    }

    @Test
    void gatewayFailureFallsBackWithStableMarker() throws Exception {
        PlanningDraftRouter router = routerWithThrow(
                new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.TIMEOUT, "timeout"));

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("worker_timeout");
        assertThat(route.marker()).isEqualTo("python_fallback:worker_timeout");
    }

    @Test
    void unavailableSchemaAndUnexpectedGatewayFailuresUseStableWorkerMarkers() {
        PlanningDraftRouter unavailable = routerWithThrow(
                new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNAVAILABLE, "down"));
        PlanningDraftRouter schema = routerWithThrow(
                new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.SCHEMA, "invalid"));
        PlanningDraftRouter unexpected = routerWithThrow(
                new PlanningDraftGatewayException(PlanningDraftGatewayErrorCode.UNEXPECTED, "unknown"));

        PlanningDraftRouter.Route unavailableRoute = unavailable.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 5000L);
        PlanningDraftRouter.Route schemaRoute = schema.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 5000L);
        PlanningDraftRouter.Route unexpectedRoute = unexpected.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(unavailableRoute.marker()).isEqualTo("python_fallback:worker_unavailable");
        assertThat(schemaRoute.marker()).isEqualTo("python_fallback:worker_schema");
        assertThat(unexpectedRoute.marker()).isEqualTo("python_fallback:worker_unexpected");
    }

    @Test
    void invalidPlanPayloadFallsBackAsSchemaFailure() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, "\"not-a-plan\""));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("schema");
        assertThat(route.marker()).isEqualTo("python_fallback:schema");
    }

    @Test
    void unknownDraftFieldFallsBackAsSchemaFailure() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, UNKNOWN_FIELD_PLAN));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("schema");
        assertThat(route.marker()).isEqualTo("python_fallback:schema");
    }

    @Test
    void emptyPlanFallsBackAsSkeletonShapeFailure() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, "{\"milestones\":[],\"tasks\":[]}"));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("skeleton_shape");
    }

    @Test
    void taskCountOverPlanBudgetFallsBackAsSkeletonShapeFailure() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, TWO_TASK_PLAN));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(
                plan(1), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("skeleton_shape");
    }

    @Test
    void canceledAttemptNeverCallsWorker() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(false);
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.canceled()).isTrue();
        assertThat(route.handled()).isFalse();
        verifyNoInteractions(gateway, snapshots);
    }

    @Test
    void javaValidatorFailureFallsBackWithoutUsingPythonAssessment() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        TaskPlanDraftValidator validator = mock(TaskPlanDraftValidator.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, VALID_PLAN));
        when(validator.assess(any(), any(), eq(TaskPlanDraftValidator.ValidationMode.AI_SKELETON),
                eq(true))).thenReturn(new ValidationAssessment(List.of(
                        new StructuredValidationIssue("TEST_HARD", ValidationIssueSeverity.HARD,
                                "PLAN", null, "test", null, Map.of()))));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, validator, new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("validator");
        verify(validator).assess(any(), any(),
                eq(TaskPlanDraftValidator.ValidationMode.AI_SKELETON), eq(true));
    }

    @Test
    void validatorExceptionFallsBackWithoutUsingPythonAssessment() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        TaskPlanDraftValidator validator = mock(TaskPlanDraftValidator.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, VALID_PLAN));
        when(validator.assess(any(), any(), eq(TaskPlanDraftValidator.ValidationMode.AI_SKELETON),
                eq(true))).thenThrow(new IllegalStateException("validator down"));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, validator, new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("validator_exception");
        verify(validator).assess(any(), any(),
                eq(TaskPlanDraftValidator.ValidationMode.AI_SKELETON), eq(true));
    }

    @Test
    void canceledAfterWorkerResponseNeverHandsDraftToOrchestrator() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true, false);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenReturn(response(json, VALID_PLAN));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.canceled()).isTrue();
        assertThat(route.handled()).isFalse();
        assertThat(route.draft()).isNull();
        verify(gateway).draft(any());
        verify(repository).appendAttemptDiagnostic(eq(PLAN_ID), eq(ATTEMPT_ID),
                org.mockito.ArgumentMatchers.argThat(entry ->
                        "PLANNING_DRAFT_ROUTE".equals(entry.get("kind"))
                                && "canceled".equals(entry.get("outcome"))
                                && "python_canceled".equals(entry.get("marker"))));
    }

    @Test
    void activeCheckFailureFallsBackInsteadOfTreatingItAsCancel() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenThrow(new IllegalStateException("db down"));
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route route = router.routeSkeleton(plan(), ATTEMPT_ID, context(), 5000L);

        assertThat(route.canceled()).isFalse();
        assertThat(route.handled()).isFalse();
        assertThat(route.reason()).isEqualTo("active_check");
        assertThat(route.marker()).isEqualTo("python_fallback:active_check");
        verifyNoInteractions(gateway, snapshots);
    }

    @Test
    void deadlineUsesSmallerOfRunBudgetAndConfiguredCap() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt())).thenReturn(null);
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        router.routeSkeleton(plan(), ATTEMPT_ID, context(), 1200L);
        router.routeSkeleton(plan(), ATTEMPT_ID, context(), 9000L);

        ArgumentCaptor<Integer> deadlines = ArgumentCaptor.forClass(Integer.class);
        verify(snapshots, times(2)).buildRequest(any(), any(), deadlines.capture());
        assertThat(deadlines.getAllValues()).containsExactly(1200, 3000);
        verify(gateway, never()).draft(any());
    }

    @Test
    void deadlineAndSnapshotUnavailableFallBackBeforeWorkerCall() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt())).thenReturn(null);
        PlanningDraftRouter router = new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON),
                gateway, snapshots, repository, new TaskPlanDraftValidator(),
                new SimpleMeterRegistry());

        PlanningDraftRouter.Route noBudget = router.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 0L);
        PlanningDraftRouter.Route noSnapshot = router.routeSkeleton(
                plan(), ATTEMPT_ID, context(), 1000L);

        assertThat(noBudget.reason()).isEqualTo("deadline");
        assertThat(noSnapshot.reason()).isEqualTo("snapshot");
        verify(gateway, never()).draft(any());
    }

    private PlanningDraftRouter routerWithThrow(PlanningDraftGatewayException failure) {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService snapshots = mock(PlanningDraftShadowService.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.active(PLAN_ID, 1L, ATTEMPT_ID, TaskPlanStatus.SKELETON_GENERATING))
                .thenReturn(true);
        when(snapshots.buildRequest(any(), any(), anyInt()))
                .thenReturn(new PlanningDraftShadowService.BoundedRequest(request(), false));
        when(gateway.draft(any())).thenThrow(failure);
        return new PlanningDraftRouter(properties(PlanningDraftMode.PYTHON), gateway, snapshots,
                repository, new TaskPlanDraftValidator(), new SimpleMeterRegistry());
    }

    private static PlanningDraftRequest request() {
        return new PlanningDraftRequest(1, "交付目标", "zh-CN",
                PlanningDraftRequest.ProjectFacts.EMPTY, "ITERATION",
                new PlanningDraftRequest.Constraints(10, 8, "2026-11-01", "2026-12-31"),
                3000);
    }

    private static PlanningDraftProperties properties(PlanningDraftMode mode) {
        return new PlanningDraftProperties(mode, "http://worker:9100", 3000, 4, 48000);
    }

    private static TaskPlanRecord plan() {
        return plan(10);
    }

    private static TaskPlanRecord plan(int maxTaskCount) {
        return new TaskPlanRecord(PLAN_ID, PROJECT_ID, "计划", "交付目标", "无",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31), maxTaskCount,
                "[]", TaskPlanStatus.SKELETON_GENERATING, 0, null, 1L, ATTEMPT_ID,
                UUID.randomUUID(), null, null, null, null);
    }

    private static ValidationContext context() {
        return new ValidationContext(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31), 10, Set.of(), Set.of());
    }

    private static PlanningDraftResponse response(ObjectMapper json, String plan) throws Exception {
        return new PlanningDraftResponse(1, json.readTree(plan), List.of(),
                new PlanningDraftResponse.Usage(10, 20, "PROVIDER"));
    }

}
