package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.api.PartialRegenerateRequest;
import com.shitulelv.aicollab.planning.api.SaveTaskPlanVersionRequest;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TaskPlanSpringBeanPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "phase08-access";
    private static final String MINIO_SECRET_KEY = "phase08-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    @Autowired TaskPlanCommandService commands;
    @Autowired TaskPlanConfirmationService confirmations;
    @Autowired TaskPlanPartialRepairService partialRepair;
    @Autowired TaskPlanVersionCommitService commits;
    @Autowired TaskPlanGenerationOrchestrator orchestrator;
    @Autowired TaskPlanRepository repository;
    @Autowired TaskPlanIssueRepository issues;
    @Autowired TaskPlanActionPolicy actionPolicy;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkReportService workReports;
    @MockitoBean TaskPlanModelClient model;

    @BeforeEach
    void resetModel() {
        reset(model);
    }

    @Test
    void productionPlanningBeansUseSpringTransactionsAndLatestFlywaySchema() {
        assertThat(commands).isNotNull();
        assertThat(confirmations).isNotNull();
        assertThat(partialRepair).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(actionPolicy).isNotNull();
        assertThat(json).isNotNull();
        assertThat(repository).isNotNull();
        assertThat(issues).isNotNull();
        assertThat(AopUtils.isAopProxy(commits)).isTrue();
        assertThat(jdbc.queryForObject(
                "select version from flyway_schema_history where success=true order by installed_rank desc limit 1",
                String.class)).isEqualTo("45");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name='ai_task_plan'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name='ai_task_plan_event'
                  and column_name='changed_targets_json'
                  and data_type='jsonb'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void createAcceptsAnyTaskLimitWithinSupportedRange() throws Exception {
        Fixture fixture = fixture("spring-custom-limit");
        stubLegalGeneration();
        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "自定义任务数规划",
                "完成系统",
                "",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                17,
                List.of());

        var created = commands.create(fixture.project(), request, fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));

        assertThat(ready.maxTaskCount()).isEqualTo(17);
    }

    @Test
    void productionBeansGenerateLatestVersionAndConfirmFormalWork() throws Exception {
        Fixture fixture = fixture("spring-ready");
        stubLegalGeneration();

        var created = commands.create(fixture.project(), request("Spring READY"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        UUID firstVersion = ready.latestVersionId();
        TaskPlanDraft firstDraft = repository.draft(
                repository.requireVersion(fixture.project(), created.id(), firstVersion));

        UUID secondVersion = commands.save(fixture.project(), created.id(),
                new SaveTaskPlanVersionRequest(firstVersion, ready.latestVersionNo(),
                        new TaskPlanDraft(firstDraft.summary() + "（已审阅）",
                                firstDraft.assumptions(), firstDraft.risks(),
                                firstDraft.milestones(), firstDraft.tasks(), firstDraft.sources()),
                        null),
                fixture.user());

        assertThatThrownBy(() -> confirmations.confirm(
                fixture.project(), created.id(), firstVersion, UUID.randomUUID(), fixture.user()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PLAN_VERSION_CONFLICT));

        var confirmed = confirmations.confirm(
                fixture.project(), created.id(), secondVersion, UUID.randomUUID(), fixture.user());
        assertThat(confirmed.get("status")).isEqualTo("SUCCESS");
        assertThat(confirmed.get("dependencyCount")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from milestone where source_plan_id=?", Integer.class, created.id())).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from project_task where source_plan_id=?", Integer.class, created.id())).isEqualTo(2);
        assertThat(repository.require(fixture.project(), created.id()).status())
                .isEqualTo(TaskPlanStatus.CONFIRMED);
        var comparison = workReports.getPlanComparison(
                fixture.project(), created.id(), fixture.user());
        assertThat(comparison.planName()).isEqualTo("Spring READY");
        assertThat(comparison.summary().totalPlanned()).isEqualTo(2);
        assertThat(comparison.summary().matchedTasks()).isEqualTo(2);
        assertThat(comparison.summary().modifiedTasks()).isZero();
        assertThat(comparison.summary().missingTasks()).isZero();
        assertThat(comparison.summary().extraTasks()).isZero();
    }

    @Test
    void productionBeansDegradeEditAndScopedRepair() throws Exception {
        Fixture fixture = fixture("spring-repair");
        stubDegradedGenerationThenPartialRepair();

        var created = commands.create(fixture.project(), request("Spring Repair"), fixture.user());
        var degraded = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY_WITH_ISSUES));
        TaskPlanVersionRecord degradedVersion = repository.requireVersion(
                fixture.project(), created.id(), degraded.latestVersionId());
        TaskPlanDraft beforeRepair = repository.draft(degradedVersion);
        var persistedIssues = issues.findPersistedByVersion(created.id(), degraded.latestVersionId());
        assertThat(persistedIssues).extracting(item -> item.issue().code())
                .contains("DEPENDENCY_DATE_CONFLICT");

        UUID issueId = persistedIssues.stream()
                .filter(item -> item.issue().code().equals("DEPENDENCY_DATE_CONFLICT"))
                .findFirst().orElseThrow().id();
        commands.partialRegenerate(fixture.project(), created.id(), new PartialRegenerateRequest(
                degraded.latestVersionId(), degraded.latestVersionNo(),
                List.of("t2"), Set.of("startDate"), Set.of(
                        "tempKey", "milestoneTempKey", "title", "objective", "sortOrder"),
                List.of(issueId), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES), fixture.user());

        var repaired = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        TaskPlanVersionRecord repairedVersion = repository.requireVersion(
                fixture.project(), created.id(), repaired.latestVersionId());
        TaskPlanDraft afterRepair = repository.draft(repairedVersion);
        assertThat(repairedVersion.sourceType()).isEqualTo("AI_PARTIAL_REPAIR");
        assertThat(task(afterRepair, "t1")).isEqualTo(task(beforeRepair, "t1"));
        assertThat(task(afterRepair, "t2").startDate()).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(task(afterRepair, "t2").title()).isEqualTo(task(beforeRepair, "t2").title());

        TaskPlanDraft conflictAgain = replaceTask(afterRepair,
                copyWithStartDate(task(afterRepair, "t2"), LocalDate.of(2026, 8, 10)));
        // The production command accepts a full editable Draft and returns to READY_WITH_ISSUES.
        UUID edited = commands.save(fixture.project(), created.id(),
                saveRequest(repaired, conflictAgain), fixture.user());
        var editable = repository.require(fixture.project(), created.id());
        assertThat(edited).isEqualTo(editable.latestVersionId());
        assertThat(editable.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);

        TaskPlanDraft resolved = replaceTask(conflictAgain,
                copyWithStartDate(task(conflictAgain, "t2"), LocalDate.of(2026, 8, 16)));
        commands.save(fixture.project(), created.id(), saveRequest(editable, resolved), fixture.user());
        assertThat(repository.require(fixture.project(), created.id()).status()).isEqualTo(TaskPlanStatus.READY);
    }

    @Test
    void productionTransactionRollsBackEventAndIssueFailures() throws Exception {
        Fixture fixture = fixture("spring-rollback");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Spring Rollback"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        TaskPlanDraft base = repository.draft(repository.requireVersion(
                fixture.project(), created.id(), ready.latestVersionId()));

        jdbc.execute("""
                create or replace function phase08_fail_event() returns trigger language plpgsql as $$
                begin raise exception 'injected event failure'; end $$;
                create trigger phase08_fail_event before insert on ai_task_plan_event
                for each row execute function phase08_fail_event()
                """);
        try {
            TaskPlanDraft changed = new TaskPlanDraft(base.summary() + " changed", base.assumptions(),
                    base.risks(), base.milestones(), base.tasks(), base.sources());
            assertThatThrownBy(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, changed), fixture.user())).isInstanceOf(RuntimeException.class);
            assertThat(repository.require(fixture.project(), created.id()).latestVersionId())
                    .isEqualTo(ready.latestVersionId());
            assertThat(repository.versions(fixture.project(), created.id())).hasSize(2);
        } finally {
            jdbc.execute("drop trigger if exists phase08_fail_event on ai_task_plan_event");
            jdbc.execute("drop function if exists phase08_fail_event()");
        }

        jdbc.execute("""
                create or replace function phase08_fail_issue() returns trigger language plpgsql as $$
                begin raise exception 'injected issue failure'; end $$;
                create trigger phase08_fail_issue before insert on ai_task_plan_validation_issue
                for each row execute function phase08_fail_issue()
                """);
        try {
            PlanTask t2 = task(base, "t2");
            TaskPlanDraft conflicting = replaceTask(base,
                    copyWithStartDate(t2, LocalDate.of(2026, 8, 10)));
            assertThatThrownBy(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, conflicting), fixture.user())).isInstanceOf(RuntimeException.class);
            assertThat(repository.require(fixture.project(), created.id()).latestVersionId())
                    .isEqualTo(ready.latestVersionId());
            assertThat(repository.versions(fixture.project(), created.id())).hasSize(2);
        } finally {
            jdbc.execute("drop trigger if exists phase08_fail_issue on ai_task_plan_validation_issue");
            jdbc.execute("drop function if exists phase08_fail_issue()");
        }
    }

    @Test
    void productionConcurrentEditsYieldOneSuccessAndOneConflict() throws Exception {
        Fixture fixture = fixture("spring-concurrent");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Spring Concurrent"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        TaskPlanDraft base = repository.draft(repository.requireVersion(
                fixture.project(), created.id(), ready.latestVersionId()));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, withSummary(base, "并发版本 A")), fixture.user()));
            var second = executor.submit(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, withSummary(base, "并发版本 B")), fixture.user()));
            int successes = 0;
            int conflicts = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof BusinessException business
                            && business.getErrorCode() == ErrorCode.PLAN_VERSION_CONFLICT) {
                        conflicts++;
                    } else {
                        throw failure;
                    }
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private void stubLegalGeneration() {
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(result(skeleton()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(result(legalDetail()));
    }

    private void stubDegradedGenerationThenPartialRepair() {
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(result(skeleton()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(result(conflictingDetail()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any(), any()))
                .thenReturn(result("{\"milestonePatches\":[],\"taskPatches\":[]}"),
                        result("{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t2\",\"startDate\":\"2026-08-16\"}]}"));
    }

    private static GenerationResult result(String content) {
        return new GenerationResult(content, "test", "deterministic", 1, 1, 1);
    }

    private static String skeleton() {
        return """
                {"summary":"规划摘要","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"发布","objective":"完成发布","targetDate":null,"sortOrder":0}],
                "tasks":[
                  {"tempKey":"t1","milestoneTempKey":"m1","title":"基础任务","objective":"完成基础","sortOrder":0},
                  {"tempKey":"t2","milestoneTempKey":"m1","title":"后续任务","objective":"完成后续","sortOrder":1}
                ]}
                """;
    }

    private static String legalDetail() {
        return """
                {"milestones":[{"tempKey":"m1","description":"发布说明","sourceRefs":[]}],
                "tasks":[
                  {"tempKey":"t1","description":"基础说明","priority":"HIGH","estimatedHours":8,
                   "startDate":"2026-08-10","dueDate":"2026-08-15","suggestedAssigneeId":null,
                   "dependencyTempKeys":[],"sourceRefs":[]},
                  {"tempKey":"t2","description":"后续说明","priority":"MEDIUM","estimatedHours":4,
                   "startDate":"2026-08-16","dueDate":"2026-08-20","suggestedAssigneeId":null,
                   "dependencyTempKeys":["t1"],"sourceRefs":[]}
                ]}
                """;
    }

    private static String conflictingDetail() {
        return legalDetail().replace("\"startDate\":\"2026-08-16\"", "\"startDate\":\"2026-08-10\"");
    }

    private Fixture fixture(String prefix) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash,display_name) values (?,?,?,?)",
                user, prefix + "-" + user.toString().substring(0, 8), "test-only-hash", "Spring 用户");
        jdbc.update("""
                insert into project(id,name,owner_id,created_by,start_date,due_date)
                values (?,?,?,?,date '2026-08-01',date '2026-08-31')
                """, project, "Spring 生产接线", user, user);
        jdbc.update("insert into project_member(project_id,user_id,role) values (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }

    private static CreateTaskPlanRequest request(String title) {
        return new CreateTaskPlanRequest(title, "完成系统", "",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 20, List.of());
    }

    private com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord awaitStatus(
            UUID project, UUID plan, Set<TaskPlanStatus> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord current;
        do {
            current = repository.require(project, plan);
            if (expected.contains(current.status())) return current;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("规划未进入预期状态，当前状态：" + current.status());
    }

    private static SaveTaskPlanVersionRequest saveRequest(
            com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord plan, TaskPlanDraft draft) {
        return new SaveTaskPlanVersionRequest(plan.latestVersionId(), plan.latestVersionNo(),
                draft, null);
    }

    private static TaskPlanDraft withSummary(TaskPlanDraft draft, String summary) {
        return new TaskPlanDraft(summary, draft.assumptions(), draft.risks(),
                draft.milestones(), draft.tasks(), draft.sources());
    }

    private static PlanTask task(TaskPlanDraft draft, String tempKey) {
        return draft.tasks().stream().filter(item -> item.tempKey().equals(tempKey))
                .findFirst().orElseThrow();
    }

    private static TaskPlanDraft replaceTask(TaskPlanDraft draft, PlanTask replacement) {
        return new TaskPlanDraft(draft.summary(), draft.assumptions(), draft.risks(),
                draft.milestones(), draft.tasks().stream()
                .map(item -> item.tempKey().equals(replacement.tempKey()) ? replacement : item).toList(),
                draft.sources());
    }

    private static PlanTask copyWithStartDate(PlanTask task, LocalDate startDate) {
        return new PlanTask(task.tempKey(), task.milestoneTempKey(), task.title(), task.objective(),
                task.description(), task.priority(), task.estimatedHours(), startDate, task.dueDate(),
                task.suggestedAssigneeId(), task.assigneeId(), task.dependencyTempKeys(),
                task.sourceRefs(), task.sortOrder());
    }

    private record Fixture(UUID user, UUID project) {
    }
}
