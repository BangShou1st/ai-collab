package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.domain.*;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestration tests O1–O4: full two-phase generation with fake gateway.
 * Uses PostgreSQL Testcontainers + mocked model client + controllable executor.
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanGenerationOrchestratorIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static TaskPlanRepository repository;
    static TransactionTemplate tx;
    static ObjectMapper json;
    static TaskPlanOutputParser parser;
    static TaskPlanDraftValidator validator;
    static PlanningPromptPolicy promptPolicy;
    static GenerationOutcomeDecider outcomeDecider;
    static TaskPlanDraftNormalizer normalizer;
    static TaskPlanVersionCommitService commitService;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        repository = new TaskPlanRepository(jdbc, json);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        parser = new TaskPlanOutputParser(json);
        validator = new TaskPlanDraftValidator();
        promptPolicy = new PlanningPromptPolicy();
        outcomeDecider = new GenerationOutcomeDecider();
        normalizer = new TaskPlanDraftNormalizer();
        TaskPlanIssueRepository issueRepo = new TaskPlanIssueRepository(jdbc, json);
        TaskPlanEventRepository eventRepo = new TaskPlanEventRepository(jdbc, json);
        commitService = new TaskPlanVersionCommitService(repository, issueRepo, eventRepo);
    }

    // ══════════════════════════════════════════════════════════════════
    // O1: Legal two-phase → READY
    // ══════════════════════════════════════════════════════════════════

    @Test
    void legalTwoPhaseGeneratesReadyPlan() throws Exception {
        UUID userId = insertUser("o1");
        UUID projectId = insertProject("O1 Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Valid skeleton (no sources/sourceRefs — S2 compliant)
        String skeletonJson = """
                {"summary":"Project plan","assumptions":["Team available"],"risks":["Timeline tight"],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":"$TGT","sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """.replace("$TGT", LocalDate.now().plusDays(20).toString());
        // Valid detail (all required fields — S3 compliant)
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase milestone","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design docs","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        // Catch-all first (lenient) — specific stubs take precedence
        lenient().when(modelClient.generate(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(null);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "test-provider", "test-model", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(detailJson, "test-provider", "test-model", 100, 50, 200));

        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        PlanSource serverSource = new PlanSource("S1", UUID.randomUUID(), "doc.pdf", UUID.randomUUID(), "Heading", 0.9, "quote", "hash");
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of(serverSource)));
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        // Create plan
        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "O1 Plan", "Test goal", "No constraints",
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        // Dispatch
        orchestrator.dispatch(plan, userId, false);

        // Wait for completion
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Verify final state
        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
        assertThat(finalPlan.activeAttemptId()).isNull();

        // Verify version exists with correct type
        List<TaskPlanVersionRecord> versions = repository.versions(projectId, plan.id());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).sourceType()).isEqualTo("AI_COMPLETE");
        assertThat(versions.get(1).sourceType()).isEqualTo("AI_SKELETON");

        // Verify draft content
        TaskPlanDraft finalDraft = repository.draft(versions.get(0));
        assertThat(finalDraft.summary()).isEqualTo("Project plan");
        assertThat(finalDraft.milestones()).hasSize(1);
        assertThat(finalDraft.tasks()).hasSize(1);
        assertThat(finalDraft.tasks().getFirst().description()).isEqualTo("Complete design docs");
        assertThat(finalDraft.tasks().getFirst().priority()).isEqualTo("HIGH");

        // Verify sources come from server context, not skeleton model
        assertThat(finalDraft.sources()).hasSize(1);
        assertThat(finalDraft.sources().getFirst().ref()).isEqualTo("S1");

        // Verify attempt metrics
verify(modelClient, times(2)).generate(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════════
    // O2: First invalid → Repair → success
    // ══════════════════════════════════════════════════════════════════

    @Test
    void firstInvalidThenRepairSucceeds() throws Exception {
        UUID userId = insertUser("o2");
        UUID projectId = insertProject("O2 Project", userId);
        insertMember(projectId, userId, "OWNER");

        // First skeleton: has sources (S2 violation — will be rejected by strict parser)
        String badSkeleton = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}],
                "sources":[{"ref":"S1"}]}
                """;
        // Repaired skeleton: no sources (S2 compliant)
        String goodSkeleton = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}]}
                """;
        // Valid detail
        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"desc","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"desc","priority":"MEDIUM",
                "estimatedHours":4.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        // First skeleton call returns bad output, repair returns good output
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(badSkeleton, "p", "m", 100, 50, 200))
                .thenReturn(new GenerationResult(goodSkeleton, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(detailJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(goodSkeleton, "p", "m", 100, 50, 200));

        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of()));
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "O2 Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.READY);
        assertThat(finalPlan.activeAttemptId()).isNull();
    }

    // ══════════════════════════════════════════════════════════════════
    // O3: Two invalid → FAILED with safe error summary
    // ══════════════════════════════════════════════════════════════════

    @Test
    void twoInvalidSkeletonsFailsWithSafeSummary() throws Exception {
        UUID userId = insertUser("o3");
        UUID projectId = insertProject("O3 Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Both skeleton outputs have sources (S2 violation)
        String badSkeleton = """
                {"summary":"s","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"M","objective":"O","targetDate":null,"sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"T","objective":"O","sortOrder":0}],
                "sources":[{"ref":"S1"}]}
                """;

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        // Both attempts return bad output
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(badSkeleton, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(badSkeleton, "p", "m", 100, 50, 200));

        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of()));
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "O3 Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.FAILED);
        assertThat(finalPlan.activeAttemptId()).isNull();
        // S4: Error summary must contain safe diagnostic info, not raw output
        assertThat(finalPlan.lastErrorCode()).isNotNull();
        assertThat(finalPlan.lastErrorSummary()).isNotNull();
        assertThat(finalPlan.lastErrorSummary()).contains("SKELETON");
        assertThat(finalPlan.lastErrorSummary()).doesNotContain("API");
        assertThat(finalPlan.lastErrorSummary()).doesNotContain("Key");
    }

    // ══════════════════════════════════════════════════════════════════
    // O4: finish_reason=length → truncated error
    // ══════════════════════════════════════════════════════════════════

    @Test
    void outputTruncatedFailsWithTruncatedErrorCode() throws Exception {
        UUID userId = insertUser("o4");
        UUID projectId = insertProject("O4 Project", userId);
        insertMember(projectId, userId, "OWNER");

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        // Simulate truncated output by throwing OUTPUT_TRUNCATED
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PLANNING_MODEL_OUTPUT_TRUNCATED));

        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of()));
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                executor, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, outcomeDecider, normalizer, commitService);

        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "O4 Plan", "Goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of());
        TaskPlanRecord plan = repository.create(projectId, userId, request);

        orchestrator.dispatch(plan, userId, false);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status()).isEqualTo(TaskPlanStatus.FAILED);
        assertThat(finalPlan.lastErrorCode()).isEqualTo("PLANNING_MODEL_OUTPUT_TRUNCATED");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,?,?)
                """, id, "orch-test-" + tag, "test-only-hash", "Orch Test " + tag);
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

    // ══════════════════════════════════════════════════════════════════
    // O5: 旧 detail 结果在"有效性检查通过→提交"之间遭遇取消→重新生成，
    //     不得借用新一轮身份提交（同步点控制时序，不靠 sleep）
    // ══════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    void staleDetailResultCannotBorrowNewGenerationIdentityAfterCancelAndRegenerate() {
        UUID userId = insertUser("o5");
        UUID projectId = insertProject("O5 Project", userId);
        insertMember(projectId, userId, "OWNER");

        TaskPlanRecord plan = repository.create(projectId, userId, new CreateTaskPlanRequest(
                "O5 Plan", "Goal", null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), 10, List.of()));

        // 与 runSkeleton 正常路径一致的持久化推进：骨架版本落库 → DETAIL_GENERATING
        TaskPlanDraft skeleton = new TaskPlanDraft(
                "Project plan", List.of("Team available"), List.of("Timeline tight"),
                List.of(new PlanMilestone("m1", "Phase 1", "Design", null,
                        LocalDate.now().plusDays(20), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "Task 1", "Do design",
                        null, null, null, null, null, null, null, List.of(), List.of(), 0)),
                List.of());
        UUID skeletonVersion = repository.appendGeneratedVersion(
                projectId, plan.id(), plan.generationSeq(), plan.activeAttemptId(),
                TaskPlanStatus.SKELETON_GENERATING, "AI_SKELETON", null, skeleton, userId,
                validator.validate(repository.validationContext(plan), skeleton, TaskPlanDraftValidator.ValidationMode.AI_SKELETON),
                TaskPlanStatus.DETAIL_GENERATING);
        assertThat(skeletonVersion).isNotNull();
        UUID detailAttempt = repository.startDetailAfterSkeleton(projectId, plan.id(), userId);
        TaskPlanRecord detailPlan = repository.require(projectId, plan.id());
        assertThat(detailPlan.status()).isEqualTo(TaskPlanStatus.DETAIL_GENERATING);
        assertThat(detailPlan.activeAttemptId()).isEqualTo(detailAttempt);

        String detailJson = """
                {"milestones":[{"tempKey":"m1","description":"Design phase milestone","sourceRefs":[]}],
                "tasks":[{"tempKey":"t1","description":"Complete design docs","priority":"HIGH",
                "estimatedHours":8.0,"startDate":"$S","dueDate":"$D",
                "suggestedAssigneeId":null,"dependencyTempKeys":[],"sourceRefs":[]}]}
                """.replace("$S", LocalDate.now().plusDays(2).toString())
                .replace("$D", LocalDate.now().plusDays(10).toString());
        String skeletonJson = """
                {"summary":"Project plan","assumptions":["Team available"],"risks":["Timeline tight"],
                "milestones":[{"tempKey":"m1","title":"Phase 1","objective":"Design","targetDate":"$TGT","sortOrder":0}],
                "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"Task 1","objective":"Do design","sortOrder":0}]}
                """.replace("$TGT", LocalDate.now().plusDays(20).toString());

        TaskPlanModelClient modelClient = mock(TaskPlanModelClient.class);
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(detailJson, "p", "m", 100, 50, 200));
        when(modelClient.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(skeletonJson, "p", "m", 100, 50, 200));
        TaskPlanContextAssembler contexts = mock(TaskPlanContextAssembler.class);
        when(contexts.assemble(any())).thenReturn(new TaskPlanContextAssembler.PlanningContext(
                "<PROJECT_DATA>test</PROJECT_DATA>", List.of()));
        when(contexts.memberSnapshot(any())).thenReturn(
                new TaskPlanContextAssembler.MemberSnapshot("成员列表", Set.of()));

        // 同步点：结果有效性检查已通过、提交开始前，稳定插入"取消→重新生成"。
        // 此时旧一轮 detail 已生成完毕，新一轮以 generation_seq+1、新 activeAttemptId 运行。
        // decideStatus 在最后一次有效性检查之后、提交之前执行，旧实现会在此间隙重新读取
        // 最新规划身份并提交——这正是旧结果借用新一轮身份的窗口。
        java.util.concurrent.atomic.AtomicBoolean raced = new java.util.concurrent.atomic.AtomicBoolean();
        GenerationOutcomeDecider hookedDecider = new GenerationOutcomeDecider() {
            @Override
            public TaskPlanStatus decideStatus(ValidationAssessment assessment) {
                if (raced.compareAndSet(false, true)) {
                    repository.cancel(projectId, plan.id());
                    repository.startGeneration(projectId, plan.id(), userId, false);
                }
                return super.decideStatus(assessment);
            }
        };

        // 同步 executor：dispatch 在当前线程完成整个 runDetail
        TaskPlanGenerationOrchestrator orchestrator = new TaskPlanGenerationOrchestrator(
                Runnable::run, repository, modelClient, parser, validator, json, contexts,
                promptPolicy, hookedDecider, normalizer, commitService);

        orchestrator.dispatch(detailPlan, userId, true);

        assertThat(raced.get()).isTrue();
        // 旧结果被丢弃：没有产生错误版本，旧 attempt 记为 DISCARDED
        List<TaskPlanVersionRecord> versions = repository.versions(projectId, plan.id());
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().sourceType()).isEqualTo("AI_SKELETON");
        Integer discarded = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id=? AND status='DISCARDED'",
                Integer.class, plan.id());
        assertThat(discarded).isEqualTo(1);
        // 新一轮未被旧结果改变：仍在 SKELETON_GENERATING，身份是新一轮自己的
        TaskPlanRecord afterRace = repository.require(projectId, plan.id());
        assertThat(afterRace.status()).isEqualTo(TaskPlanStatus.SKELETON_GENERATING);
        assertThat(afterRace.generationSeq()).isGreaterThan(detailPlan.generationSeq());
        assertThat(afterRace.activeAttemptId()).isNotNull().isNotEqualTo(detailAttempt);

        // 新一轮仍能正常完成
        orchestrator.dispatch(repository.require(projectId, plan.id()), userId, false);
        TaskPlanRecord finalPlan = repository.require(projectId, plan.id());
        assertThat(finalPlan.status())
                .as(() -> "lastError=" + finalPlan.lastErrorCode() + " / " + finalPlan.lastErrorSummary())
                .isEqualTo(TaskPlanStatus.READY);
        assertThat(finalPlan.generationSeq()).isEqualTo(afterRace.generationSeq());
        assertThat(repository.versions(projectId, plan.id())).hasSize(3);
    }
}
