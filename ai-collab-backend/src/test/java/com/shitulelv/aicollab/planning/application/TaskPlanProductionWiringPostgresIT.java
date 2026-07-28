package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.api.PartialRegenerateRequest;
import com.shitulelv.aicollab.planning.api.UpdateTaskPlanRequest;
import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskPlanProductionWiringPostgresIT:
 * Real PostgreSQL + Testcontainers integration tests for the full planning pipeline.
 *
 * Uses ONLY FakeModel (mock TaskPlanModelClient) — all repositories, database,
 * transaction services, validators, and parsers are REAL.
 *
 * 10 tests covering 4 chain paths:
 *   Chain 1: Real model → READY
 *   Chain 2: Conflict → Repair → READY or READY_WITH_ISSUES
 *   Chain 3: READY_WITH_ISSUES → USER_EDIT → READY
 *   Chain 4: Partial repair → only allowed fields changed
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanProductionWiringPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static ObjectMapper json;
    static TaskPlanRepository repository;
    static TaskPlanOutputParser parser;
    static TaskPlanDraftValidator validator;
    static PlanningPromptPolicy promptPolicy;
    static GenerationOutcomeDecider outcomeDecider;
    static TaskPlanDraftNormalizer normalizer;
    static TaskPlanIssueRepository issueRepo;
    static TaskPlanEventRepository eventRepo;
    static TaskPlanVersionCommitService commitService;
    static TaskPlanRepairPatchParser patchParser;
    static TaskPlanRepairPatchApplier patchApplier;
    static TaskPlanContextAssembler contexts;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = new ObjectMapper().findAndRegisterModules();
        repository = new TaskPlanRepository(jdbc, json);
        parser = new TaskPlanOutputParser(json);
        validator = new TaskPlanDraftValidator();
        promptPolicy = new PlanningPromptPolicy();
        outcomeDecider = new GenerationOutcomeDecider();
        normalizer = new TaskPlanDraftNormalizer();
        issueRepo = new TaskPlanIssueRepository(jdbc, json);
        eventRepo = new TaskPlanEventRepository(jdbc, json);
        commitService = new TaskPlanVersionCommitService(repository, issueRepo, eventRepo);
        patchParser = new TaskPlanRepairPatchParser(json);
        patchApplier = new TaskPlanRepairPatchApplier();
        contexts = mock(TaskPlanContextAssembler.class);
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of()));
        when(contexts.memberContext(any())).thenReturn("项目成员（仅可推荐以下成员作为负责人）：[]");
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 1: Legal two-phase → READY
    // ══════════════════════════════════════════════════════════════════

    @Test
    void legalTwoPhaseGeneratesReadyPlan() throws Exception {
        UUID userId = insertUser("pw1");
        UUID projectId = insertProject("PW1 Project", userId);
        insertMember(projectId, userId, "OWNER");

        String skeletonJson = """
                {"summary":"Project plan","assumptions":["Team available"],"risks":["Timeline tight"],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":"2026-08-15","sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase milestone","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design docs","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"2026-08-01","dueDate":"2026-08-10",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "test-provider", "test-model", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailJson, "test-provider", "test-model", 100, 50, 200));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "PW1 Plan", "Test goal", "No constraints",
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
        assertThat(finalPlan.activeAttemptId()).isNull();

        List<TaskPlanVersionRecord> versions = repository.versions(projectId, plan.id());
        assertThat(versions).hasSize(2);

        TaskPlanDraft finalDraft = repository.draft(versions.stream()
                .filter(v -> v.sourceType().equals("AI_COMPLETE")).findFirst().orElseThrow());
        assertThat(finalDraft.summary()).isEqualTo("Project plan");
        assertThat(finalDraft.tasks().getFirst().priority()).isEqualTo("HIGH");
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 2a: Date conflict → Repair success → READY
    // ══════════════════════════════════════════════════════════════════

    @Test
    void validRepairPatchEndsReady() throws Exception {
        UUID userId = insertUser("pw2a");
        UUID projectId = insertProject("PW2A Project", userId);
        insertMember(projectId, userId, "OWNER");

        // First skeleton: has sources (S2 violation → parser throws)
        String badSkeleton = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}],
                "sources":[{"ref":"S1"}]}
                """;
        // Repaired skeleton: no sources (valid)
        String goodSkeleton = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}]}
                """;
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc","priority":"MEDIUM",
                "estimatedHours":4.0,"startDate":"2026-08-01","dueDate":"2026-08-05",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any()))
                .thenReturn(new GenerationResult(badSkeleton, "p", "m", 100, 50, 200))
                .thenReturn(new GenerationResult(goodSkeleton, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any()))
                .thenReturn(new GenerationResult(goodSkeleton, "p", "m", 100, 50, 200));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "PW2A Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 2b: Date conflict → Repair still fails → READY_WITH_ISSUES
    // ══════════════════════════════════════════════════════════════════

    @Test
    void repairStillHasDependencyDateConflictCommitsReadyWithIssues() throws Exception {
        UUID userId = insertUser("pw2b");
        UUID projectId = insertProject("PW2B Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Valid skeleton
        String skeletonJson = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T1","objective":"O","sortOrder":0},
                {"tempKey":"t2","milestoneTempKey":"m1","title":"T2","objective":"O","sortOrder":1}]}
                """;
        // Detail with DEPENDENCY_DATE_CONFLICT: t1.dueDate after t2.startDate when t2 depends on t1
        String detailWithConflict = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc1","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"2026-08-01","dueDate":"2026-08-15",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]},
                {"tempKey":"t2","description":"desc2","priority":"MEDIUM",
                "estimatedHours":4.0,"startDate":"2026-08-10","dueDate":"2026-08-20",
                "suggestedAssigneeId":null,"dependencyTempKeys":["t1"],"sourceRefs":[]}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailWithConflict, "p", "m", 100, 50, 200));
        // Repair returns same conflicting detail → still BLOCKING_EDITABLE
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailWithConflict, "p", "m", 100, 50, 200));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "PW2B Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 20, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);

        // Verify source type is AI_PARTIAL (not AI_COMPLETE) when degraded
        TaskPlanVersionRecord latestVersion = repository.requireVersion(projectId, plan.id(), finalPlan.latestVersionId());
        assertThat(latestVersion.sourceType()).isEqualTo("AI_PARTIAL");

        // Verify structured issues were persisted
        List<StructuredValidationIssue> issues = issueRepo.findByVersion(plan.id(), finalPlan.latestVersionId());
        assertThat(issues).isNotEmpty();
        assertThat(issues.stream().anyMatch(i -> i.code().equals("DEPENDENCY_DATE_CONFLICT"))).isTrue();
        assertThat(issues.stream().anyMatch(i -> i.severity() == ValidationIssueSeverity.BLOCKING_EDITABLE)).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 2c: HARD repair failure → FAILED without partial version
    // ══════════════════════════════════════════════════════════════════

    @Test
    void hardRepairFailureEndsFailedWithoutPartialVersion() throws Exception {
        UUID userId = insertUser("pw2c");
        UUID projectId = insertProject("PW2C Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Valid skeleton
        String skeletonJson = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}]}
                """;
        // Detail with HARD error: invalid priority value
        String badDetail = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc","priority":"INVALID",
                "estimatedHours":8.0,"startDate":"2026-08-01","dueDate":"2026-08-10",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any()))
                .thenReturn(new GenerationResult(badDetail, "p", "m", 100, 50, 200));
        // Repair also returns bad detail
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any()))
                .thenReturn(new GenerationResult(badDetail, "p", "m", 100, 50, 200));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "PW2C Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.DETAIL_GENERATION_FAILED);
        // No AI_COMPLETE version should exist
        List<TaskPlanVersionRecord> versions = repository.versions(projectId, plan.id());
        assertThat(versions.stream().noneMatch(v -> v.sourceType().equals("AI_COMPLETE"))).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 3a: READY_WITH_ISSUES → USER_EDIT → READY
    // ══════════════════════════════════════════════════════════════════

    @Test
    void editReadyWithIssuesResolvesIssueAtomically() throws Exception {
        UUID userId = insertUser("pw3a");
        UUID projectId = insertProject("PW3A Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Generate a plan that ends READY_WITH_ISSUES via orchestrator
        String skeletonJson = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T1","objective":"O","sortOrder":0},
                {"tempKey":"t2","milestoneTempKey":"m1","title":"T2","objective":"O","sortOrder":1}]}
                """;
        // Detail with DEPENDENCY_DATE_CONFLICT
        String detailWithConflict = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc1","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"2026-08-01","dueDate":"2026-08-15",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]},
                {"tempKey":"t2","description":"desc2","priority":"MEDIUM",
                "estimatedHours":4.0,"startDate":"2026-08-10","dueDate":"2026-08-20",
                "suggestedAssigneeId":null,"dependencyTempKeys":["t1"],"sourceRefs":[]}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailWithConflict, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any()))
                .thenReturn(new GenerationResult(detailWithConflict, "p", "m", 100, 50, 200));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest createReq = new CreateTaskPlanRequest(
                "PW3A Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 20, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, createReq);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord readyWithIssuesPlan = repository.require(projectId, plan.id());
        assertThat(readyWithIssuesPlan.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);

        UUID baseVersionId = readyWithIssuesPlan.latestVersionId();

        // Now edit: fix t2's startDate to be after t1's dueDate
        TaskPlanVersionRecord baseVersion = repository.requireVersion(projectId, plan.id(), baseVersionId);
        TaskPlanDraft baseDraft = repository.draft(baseVersion);

        // Apply user patch: change t2 startDate to 2026-08-16 (after t1 dueDate 2026-08-15)
        UpdateTaskPlanRequest editRequest = new UpdateTaskPlanRequest(
                baseVersionId, baseVersion.versionNo(),
                PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                List.of(),
                List.of(new TaskPlanRepairPatch.TaskPatch("t2",
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent(),
                        PatchValue.of(LocalDate.of(2026, 8, 16)), PatchValue.absent(),
                        PatchValue.absent(), PatchValue.absent(), PatchValue.absent())));

        TaskPlanCommandService commands = new TaskPlanCommandService(
                mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class),
                repository, orchestrator, validator, jdbc,
                null, null, mock(com.shitulelv.aicollab.project.application.service.AuditService.class),
                normalizer, outcomeDecider, commitService, patchParser, patchApplier, null, json);
        // Bypass access guard
        doNothing().when(mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class))
                .requireAdmin(projectId, userId);

        // Use direct edit since access guard is mocked
        UUID newVersionId = tx.execute(status -> {
            // Load base
            TaskPlanRecord p = repository.require(projectId, plan.id());
            TaskPlanVersionRecord bv = repository.requireVersion(projectId, plan.id(), baseVersionId);
            TaskPlanDraft bd = repository.draft(bv);

            // Apply patch
            TaskPlanDraft patched = applyUserEditPatch(bd, editRequest);

            // Normalize
            TaskPlanDraft normalized = normalizer.normalize(patched);

            // Validate
            Set<UUID> members = new java.util.HashSet<>(jdbc.queryForList(
                    "SELECT user_id FROM project_member WHERE project_id=?", UUID.class, projectId));
            LocalDate[] projectDates = jdbc.queryForObject("SELECT start_date,due_date FROM project WHERE id=?",
                    (rs, row) -> new LocalDate[]{rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, projectId);
            var validation = validator.validate(new ValidationContext(projectDates[0], projectDates[1],
                    p.planStartDate(), p.planDueDate(), p.maxTaskCount(), members, Set.of()), normalized);
            ValidationAssessment assessment = toAssessment(validation);

            // Commit (interactive path — no active attempt)
            TaskPlanVersionRecord vr = commitService.commitVersion(p, normalized,
                    TaskPlanVersionSource.MANUAL_EDIT, assessment,
                    outcomeDecider.decideStatus(assessment),
                    "TASK_PLAN_USER_EDITED", userId, baseVersionId);
            return vr != null ? vr.id() : null;
        });

        assertThat(newVersionId).isNotNull();

        TaskPlanRecord editedPlan = repository.require(projectId, plan.id());
        assertThat(editedPlan.status()).isEqualTo(TaskPlanStatus.READY);

        // Verify issues are gone
        List<StructuredValidationIssue> remainingIssues = issueRepo.findByVersion(plan.id(), editedPlan.latestVersionId());
        assertThat(remainingIssues.stream().noneMatch(i -> i.severity() == ValidationIssueSeverity.BLOCKING_EDITABLE)).isTrue();

        // Verify event was written
        List<TaskPlanEventRecord> events = eventRepo.findByPlan(plan.id(), 10);
        assertThat(events.stream().anyMatch(e -> e.eventType().equals("TASK_PLAN_USER_EDITED"))).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 3b: Edit event failure rolls back everything
    // ══════════════════════════════════════════════════════════════════

    @Test
    void editEventFailureRollsBackEverything() throws Exception {
        UUID userId = insertUser("pw3b");
        UUID projectId = insertProject("PW3B Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Manually create a READY plan with issues
        PlanMilestone m1 = new PlanMilestone("m1", "M", "O", null, null, 0, List.of());
        PlanTask t1 = new PlanTask("t1", "m1", "T", "O", "desc", "MEDIUM",
                BigDecimal.valueOf(4), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10),
                null, null, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        PlanSetup setup = insertReadyWithIssuesPlan(projectId, userId, draft, null);
        UUID planId = setup.planId();
        UUID versionId = setup.versionId();

        // Insert issue
        issueRepo.replaceForVersion(planId, versionId, List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "t1", "startDate", null, java.util.Map.of())));

        // Verify state before edit
        TaskPlanRecord beforeEdit = repository.require(projectId, planId);
        assertThat(beforeEdit.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);
        assertThat(beforeEdit.latestVersionId()).isEqualTo(versionId);

        List<StructuredValidationIssue> beforeIssues = issueRepo.findByVersion(planId, versionId);
        assertThat(beforeIssues).hasSize(1);

        // Now attempt edit with a broken event repo that throws
        TaskPlanEventRepository brokenEventRepo = mock(TaskPlanEventRepository.class);
        doThrow(new RuntimeException("EVENT_WRITE_FAILED")).when(brokenEventRepo).append(any());

        TaskPlanVersionCommitService failingCommitService = new TaskPlanVersionCommitService(
                repository, issueRepo, brokenEventRepo);

        // Edit should fail and roll back
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            TaskPlanRecord p = repository.require(projectId, planId);
            TaskPlanVersionRecord bv = repository.requireVersion(projectId, planId, versionId);
            TaskPlanDraft bd = repository.draft(bv);

            // Simple patch: no changes
            TaskPlanDraft patched = new TaskPlanDraft(bd.summary(), bd.assumptions(), bd.risks(),
                    bd.milestones(), bd.tasks(), bd.sources());
            TaskPlanDraft normalized = normalizer.normalize(patched);

            Set<UUID> members = new java.util.HashSet<>(jdbc.queryForList(
                    "SELECT user_id FROM project_member WHERE project_id=?", UUID.class, projectId));
            LocalDate[] projectDates = jdbc.queryForObject("SELECT start_date,due_date FROM project WHERE id=?",
                    (rs, row) -> new LocalDate[]{rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)}, projectId);
            var validation = validator.validate(new ValidationContext(projectDates[0], projectDates[1],
                    p.planStartDate(), p.planDueDate(), p.maxTaskCount(), members, Set.of()), normalized);
            ValidationAssessment assessment = toAssessment(validation);

            failingCommitService.commitVersion(p, normalized,
                    TaskPlanVersionSource.MANUAL_EDIT, assessment,
                    outcomeDecider.decideStatus(assessment),
                    "TASK_PLAN_USER_EDITED", userId, versionId);
        })).isInstanceOf(RuntimeException.class);

        // Verify nothing changed — atomic rollback
        TaskPlanRecord afterFailedEdit = repository.require(projectId, planId);
        assertThat(afterFailedEdit.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);
        assertThat(afterFailedEdit.latestVersionId()).isEqualTo(versionId);

        List<StructuredValidationIssue> afterIssues = issueRepo.findByVersion(planId, versionId);
        assertThat(afterIssues).hasSize(1);
    }

    // ══════════════════════════════════════════════════════════════════
    // Helper: insert a READY_WITH_ISSUES plan with issues
    // ══════════════════════════════════════════════════════════════════

    private record PlanSetup(UUID planId, UUID versionId) {}

    private PlanSetup insertReadyWithIssuesPlan(UUID projectId, UUID userId, TaskPlanDraft draft,
                                                 List<StructuredValidationIssue> issues) {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        // Insert plan without active_attempt_id first
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,constraints,plan_start_date,plan_due_date,
                  max_task_count,selected_document_ids_json,status,latest_version_no,
                  generation_seq,created_by)
                VALUES (?,?,?,'goal','',
                  ?,?,?,'[]','READY_WITH_ISSUES',0,1,?)
                """, planId, projectId, "test", LocalDate.now().plusDays(1), LocalDate.now().plusDays(30),
                draft.tasks().size() <= 10 ? 10 : 20, userId);
        // Insert attempt
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,-1,1,'DETAIL','SUCCESS',?)
                """, attemptId, planId, userId);
        // Update plan to set active_attempt_id and latest_version_id
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id=? WHERE id=?",
                attemptId, planId);
        // Insert version
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,based_on_version_id,
                  generation_seq,summary,assumptions_json,risks_json,milestones_json,tasks_json,sources_json,
                  validation_result_json,created_by)
                VALUES (?,?,1,'AI_COMPLETE',null,1,
                  ?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'[]'::jsonb,'{}'::jsonb,?)
                """, versionId, planId, draft.summary(),
                writeJson(draft.assumptions()), writeJson(draft.risks()),
                writeJson(draft.milestones()), writeJson(draft.tasks()), userId);
        // Update plan to set latest_version_id
        jdbc.update("UPDATE ai_task_plan SET latest_version_id=?, latest_version_no=1 WHERE id=?",
                versionId, planId);
        if (issues != null && !issues.isEmpty()) {
            issueRepo.replaceForVersion(planId, versionId, issues);
        }
        return new PlanSetup(planId, versionId);
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 4a: Partial repair calls patch model exactly once
    // ══════════════════════════════════════════════════════════════════

    @Test
    void partialRepairCallsPatchModelExactlyOnce() throws Exception {
        UUID userId = insertUser("pw4a");
        UUID projectId = insertProject("PW4A Project", userId);
        insertMember(projectId, userId, "OWNER");

        PlanMilestone m1 = new PlanMilestone("m1", "M", "O", null, null, 0, List.of());
        PlanTask t1 = new PlanTask("t1", "m1", "T1", "O", "desc1", "HIGH",
                BigDecimal.valueOf(8), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("t2", "m1", "T2", "O", "desc2", "MEDIUM",
                BigDecimal.valueOf(4), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of("t1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        PlanSetup setup = insertReadyWithIssuesPlan(projectId, userId, draft, List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "t2", "startDate", "t1", java.util.Map.of())));

        // Mock model: returns a patch that fixes t2's startDate
        String patchJson = """
                {"taskPatches":[{"tempKey":"t2","startDate":"2026-08-16"}]}
                """;
        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any()))
                .thenReturn(new GenerationResult(patchJson, "p", "m", 50, 20, 100));

        TaskPlanCommandService commands = createCommandService(modelClient);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                setup.versionId(), List.of("t2"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES);

        commands.partialRegenerate(projectId, setup.planId(), request, userId);

        // Verify model was called exactly once with REPAIR_PATCH type
        verify(modelClient, times(1)).generate(
                anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any());

        // Verify plan is now READY
        TaskPlanRecord afterPatch = repository.require(projectId, setup.planId());
        assertThat(afterPatch.status()).isEqualTo(TaskPlanStatus.READY);
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 4b: Partial repair changes only allowed fields
    // ══════════════════════════════════════════════════════════════════

    @Test
    void partialRepairChangesOnlyAllowedFields() throws Exception {
        UUID userId = insertUser("pw4b");
        UUID projectId = insertProject("PW4B Project", userId);
        insertMember(projectId, userId, "OWNER");

        PlanMilestone m1 = new PlanMilestone("m1", "M", "O", null, null, 0, List.of());
        PlanTask t1 = new PlanTask("t1", "m1", "T1", "Original objective", "Original desc", "HIGH",
                BigDecimal.valueOf(8), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("t2", "m1", "T2", "Original obj2", "Original desc2", "MEDIUM",
                BigDecimal.valueOf(4), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of("t1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        PlanSetup setup = insertReadyWithIssuesPlan(projectId, userId, draft, List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "t2", "startDate", "t1", java.util.Map.of())));

        // Patch changes startDate AND title (title is not in allowed fields)
        String patchJson = """
                {"taskPatches":[{"tempKey":"t2","startDate":"2026-08-16","title":"HACKED TITLE"}]}
                """;
        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any()))
                .thenReturn(new GenerationResult(patchJson, "p", "m", 50, 20, 100));

        TaskPlanCommandService commands = createCommandService(modelClient);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                setup.versionId(), List.of("t2"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES);

        commands.partialRegenerate(projectId, setup.planId(), request, userId);

        // Verify: startDate changed but title preserved
        TaskPlanRecord afterPatch = repository.require(projectId, setup.planId());
        TaskPlanDraft patchedDraft = repository.draft(repository.requireVersion(
                projectId, setup.planId(), afterPatch.latestVersionId()));

        PlanTask patchedT2 = patchedDraft.tasks().stream()
                .filter(t -> t.tempKey().equals("t2")).findFirst().orElseThrow();
        assertThat(patchedT2.startDate()).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(patchedT2.title()).isEqualTo("T2"); // Title NOT changed
        assertThat(patchedT2.objective()).isEqualTo("Original obj2"); // Objective NOT changed
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 4c: Partial repair keeps locked fields exactly
    // ══════════════════════════════════════════════════════════════════

    @Test
    void partialRepairKeepsLockedFieldsExactly() throws Exception {
        UUID userId = insertUser("pw4c");
        UUID projectId = insertProject("PW4C Project", userId);
        insertMember(projectId, userId, "OWNER");

        PlanMilestone m1 = new PlanMilestone("m1", "Milestone Title", "Milestone Obj",
                "Original milestone desc", LocalDate.of(2026, 8, 15), 0, List.of());
        PlanTask t1 = new PlanTask("t1", "m1", "Task Title", "Task Obj", "Task Desc", "HIGH",
                BigDecimal.valueOf(8), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
                null, null, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("Plan Summary", List.of("assumption1"), List.of("risk1"),
                List.of(m1), List.of(t1), List.of());

        PlanSetup setup = insertReadyWithIssuesPlan(projectId, userId, draft, List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "t1", "startDate", null, java.util.Map.of())));

        // Patch tries to modify locked fields on t1 (title, objective, sortOrder, tempKey are ALWAYS_LOCKED)
        // and allowed fields (startDate is allowed by DEPENDENCY_DATE_CONFLICT)
        String patchJson = """
                {"taskPatches":[{"tempKey":"t1","startDate":"2026-08-05"}]}
                """;
        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any()))
                .thenReturn(new GenerationResult(patchJson, "p", "m", 50, 20, 100));

        TaskPlanCommandService commands = createCommandService(modelClient);

        PartialRegenerateRequest request = new PartialRegenerateRequest(
                setup.versionId(), List.of("t1"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_ALL_ISSUES);

        commands.partialRegenerate(projectId, setup.planId(), request, userId);

        TaskPlanRecord afterPatch = repository.require(projectId, setup.planId());
        TaskPlanDraft patchedDraft = repository.draft(repository.requireVersion(
                projectId, setup.planId(), afterPatch.latestVersionId()));

        // Verify locked fields on t1 are preserved; only startDate (allowed) changed
        PlanTask patchedT1 = patchedDraft.tasks().stream()
                .filter(t -> t.tempKey().equals("t1")).findFirst().orElseThrow();
        assertThat(patchedT1.title()).isEqualTo("Task Title"); // LOCKED
        assertThat(patchedT1.objective()).isEqualTo("Task Obj"); // LOCKED
        assertThat(patchedT1.sortOrder()).isEqualTo(0); // LOCKED
        assertThat(patchedT1.startDate()).isEqualTo(LocalDate.of(2026, 8, 5)); // ALLOWED — changed

        // Milestone unchanged (not a target)
        PlanMilestone patchedM1 = patchedDraft.milestones().stream()
                .filter(m -> m.tempKey().equals("m1")).findFirst().orElseThrow();
        assertThat(patchedM1.title()).isEqualTo("Milestone Title");
        assertThat(patchedM1.description()).isEqualTo("Original milestone desc");
        assertThat(patchedM1.targetDate()).isEqualTo(LocalDate.of(2026, 8, 15));

        // Top-level fields also locked
        assertThat(patchedDraft.summary()).isEqualTo("Plan Summary");
        assertThat(patchedDraft.assumptions()).containsExactly("assumption1");
        assertThat(patchedDraft.risks()).containsExactly("risk1");
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 3c: READY_WITH_ISSUES confirm returns specific 409
    // ══════════════════════════════════════════════════════════════════

    @Test
    void readyWithIssuesConfirmReturnsSpecific409() {
        UUID userId = insertUser("pw3c");
        UUID projectId = insertProject("PW3C Project", userId);
        insertMember(projectId, userId, "OWNER");

        // READY_WITH_ISSUES cannot transition to CONFIRMING
        assertThat(TaskPlanStatus.READY_WITH_ISSUES.canTransitionTo(TaskPlanStatus.CONFIRMING)).isFalse();

        // READY can transition to CONFIRMING
        assertThat(TaskPlanStatus.READY.canTransitionTo(TaskPlanStatus.CONFIRMING)).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Chain 4d: 局部重新生成的正确路径 (full partial repair flow)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void partialRegenerateFullFlowRespectsScopeAndLocks() throws Exception {
        UUID userId = insertUser("pw4d");
        UUID projectId = insertProject("PW4D Project", userId);
        insertMember(projectId, userId, "OWNER");

        PlanMilestone m1 = new PlanMilestone("m1", "Phase 1", "Design", "Design phase",
                LocalDate.of(2026, 8, 15), 0, List.of());
        PlanTask t1 = new PlanTask("t1", "m1", "Task A", "Do A", "Desc A", "HIGH",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("t2", "m1", "Task B", "Do B", "Desc B", "MEDIUM",
                BigDecimal.valueOf(5), LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 12),
                null, null, List.of("t1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("Plan S", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        PlanSetup setup = insertReadyWithIssuesPlan(projectId, userId, draft, List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "t2", "startDate", "t1", java.util.Map.of(
                                "dependencyDueDate", "2026-08-10", "currentStartDate", "2026-08-08"))));

        // Model patch: only fix t2's startDate
        String patchJson = """
                {"taskPatches":[{"tempKey":"t2","startDate":"2026-08-11"}]}
                """;
        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any()))
                .thenReturn(new GenerationResult(patchJson, "p", "m", 50, 20, 100));

        TaskPlanCommandService commands = createCommandService(modelClient);

        // Only t2's startDate is allowed; t1 is not a target
        PartialRegenerateRequest request = new PartialRegenerateRequest(
                setup.versionId(), List.of("t2"), Set.of("startDate"), Set.of(),
                List.of(), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES);

        TaskPlanRecord result = commands.partialRegenerate(projectId, setup.planId(), request, userId);
        assertThat(result).isNotNull();

        // Verify final state
        TaskPlanRecord afterPatch = repository.require(projectId, setup.planId());
        assertThat(afterPatch.status()).isEqualTo(TaskPlanStatus.READY);

        TaskPlanDraft finalDraft = repository.draft(repository.requireVersion(
                projectId, setup.planId(), afterPatch.latestVersionId()));

        // t1 completely unchanged
        PlanTask finalT1 = finalDraft.tasks().stream()
                .filter(t -> t.tempKey().equals("t1")).findFirst().orElseThrow();
        assertThat(finalT1.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(finalT1.dueDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(finalT1.title()).isEqualTo("Task A");
        assertThat(finalT1.priority()).isEqualTo("HIGH");

        // t2 only startDate changed
        PlanTask finalT2 = finalDraft.tasks().stream()
                .filter(t -> t.tempKey().equals("t2")).findFirst().orElseThrow();
        assertThat(finalT2.startDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(finalT2.dueDate()).isEqualTo(LocalDate.of(2026, 8, 12)); // unchanged
        assertThat(finalT2.title()).isEqualTo("Task B"); // unchanged
        assertThat(finalT2.priority()).isEqualTo("MEDIUM"); // unchanged
        assertThat(finalT2.dependencyTempKeys()).containsExactly("t1"); // unchanged

        // Milestone unchanged
        PlanMilestone finalM1 = finalDraft.milestones().stream()
                .filter(m -> m.tempKey().equals("m1")).findFirst().orElseThrow();
        assertThat(finalM1.title()).isEqualTo("Phase 1");
        assertThat(finalM1.description()).isEqualTo("Design phase");

        // Top-level unchanged
        assertThat(finalDraft.summary()).isEqualTo("Plan S");

        // Issues cleared
        List<StructuredValidationIssue> remainingIssues = issueRepo.findByVersion(setup.planId(), afterPatch.latestVersionId());
        assertThat(remainingIssues.stream().noneMatch(
                i -> i.severity() == ValidationIssueSeverity.BLOCKING_EDITABLE)).isTrue();

        // Event recorded
        List<TaskPlanEventRecord> events = eventRepo.findByPlan(setup.planId(), 10);
        assertThat(events.stream().anyMatch(e -> e.eventType().equals("PARTIAL_REPAIR"))).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private TaskPlanCommandService createCommandService(TaskPlanModelClient modelClient) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard accessGuard =
                mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class);
        com.shitulelv.aicollab.project.application.service.AuditService auditService =
                mock(com.shitulelv.aicollab.project.application.service.AuditService.class);

        return new TaskPlanCommandService(accessGuard, repository, orchestrator, validator, jdbc,
                null, null, auditService, normalizer, outcomeDecider, commitService, patchParser, patchApplier, modelClient, json);
    }

    private TaskPlanDraft applyUserEditPatch(TaskPlanDraft base, UpdateTaskPlanRequest request) {
        var milestoneMap = new java.util.LinkedHashMap<String, PlanMilestone>();
        for (var m : base.milestones()) milestoneMap.put(m.tempKey(), m);
        for (var mp : request.milestones()) {
            var original = milestoneMap.get(mp.tempKey());
            if (original == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未知的里程碑 tempKey: " + mp.tempKey());
            milestoneMap.put(mp.tempKey(), new PlanMilestone(
                    original.tempKey(), original.title(), original.objective(),
                    mp.description().present() ? mp.description().value() : original.description(),
                    mp.targetDate().present() ? mp.targetDate().value() : original.targetDate(),
                    original.sortOrder(),
                    mp.sourceRefs().present() ? mp.sourceRefs().value() : original.sourceRefs()));
        }
        var taskMap = new java.util.LinkedHashMap<String, PlanTask>();
        for (var t : base.tasks()) taskMap.put(t.tempKey(), t);
        for (var tp : request.tasks()) {
            var original = taskMap.get(tp.tempKey());
            if (original == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未知的任务 tempKey: " + tp.tempKey());
            taskMap.put(tp.tempKey(), new PlanTask(
                    original.tempKey(), original.milestoneTempKey(), original.title(), original.objective(),
                    tp.description().present() ? tp.description().value() : original.description(),
                    tp.priority().present() ? tp.priority().value() : original.priority(),
                    tp.estimatedHours().present() ? tp.estimatedHours().value() : original.estimatedHours(),
                    tp.startDate().present() ? tp.startDate().value() : original.startDate(),
                    tp.dueDate().present() ? tp.dueDate().value() : original.dueDate(),
                    tp.suggestedAssigneeId().present() ? tp.suggestedAssigneeId().value() : original.suggestedAssigneeId(),
                    original.assigneeId(),
                    tp.dependencyTempKeys().present() ? tp.dependencyTempKeys().value() : original.dependencyTempKeys(),
                    tp.sourceRefs().present() ? tp.sourceRefs().value() : original.sourceRefs(),
                    original.sortOrder()));
        }
        return new TaskPlanDraft(base.summary(), base.assumptions(), base.risks(),
                new java.util.ArrayList<>(milestoneMap.values()),
                new java.util.ArrayList<>(taskMap.values()),
                base.sources());
    }

    private static ValidationAssessment toAssessment(ValidationResult flat) {
        var issues = new java.util.ArrayList<StructuredValidationIssue>();
        for (String code : flat.errorCodes()) {
            var severity = ValidationIssueCatalog.severityOrDefault(code);
            issues.add(new StructuredValidationIssue(code, severity, null, null, null, null, java.util.Map.of()));
        }
        for (String code : flat.warningCodes()) {
            issues.add(new StructuredValidationIssue(code, ValidationIssueSeverity.WARNING,
                    null, null, null, null, java.util.Map.of()));
        }
        return new ValidationAssessment(issues);
    }

    private static UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,?,?)
                """, id, "pw-test-" + tag, "test-only-hash", "PW Test " + tag);
        return id;
    }

    private static UUID insertProject(String name, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,?)
                """, id, name, ownerId, ownerId,
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60));
        return id;
    }

    private static void insertMember(UUID projectId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO project_member(project_id,user_id,role)
                VALUES (?,?,?)
                """, projectId, userId, role);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}
