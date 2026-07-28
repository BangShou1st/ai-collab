package com.shitulelv.aicollab.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.application.TaskPlanCommandService;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.application.service.AuditService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * RED tests for TaskPlanRepository.create() foreign-key-safe insert order.
 *
 * Before the fix, repository.create() inserted the plan row with active_attempt_id
 * referencing an attempt that did not yet exist, violating
 * fk_ai_task_plan_active_attempt and throwing DataIntegrityViolationException (500).
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static TaskPlanRepository repository;
    static TransactionTemplate tx;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new TaskPlanRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * RED-1: repository.create() must persist both plan and attempt rows,
     * link them correctly, and not violate fk_ai_task_plan_active_attempt.
     */
    @Test
    void createPersistsPlanThenAttemptWithoutViolatingActiveAttemptForeignKey() {
        // Arrange
        UUID userId = insertUser("repo-red-1");
        UUID projectId = insertProject("Red-1 Project", userId);
        insertMember(projectId, userId, "OWNER");
        CreateTaskPlanRequest request = validRequest();

        // Act
        TaskPlanRecord result = repository.create(projectId, userId, request);

        // Assert: plan row
        assertThat(result).isNotNull();
        assertThat(result.id()).isNotNull();
        assertThat(result.projectId()).isEqualTo(projectId);
        assertThat(result.title()).isEqualTo("Red-1 Plan");
        assertThat(result.status()).isEqualTo(TaskPlanStatus.SKELETON_GENERATING);
        assertThat(result.createdBy()).isEqualTo(userId);
        assertThat(result.generationSeq()).isEqualTo(1);

        // Assert: active_attempt_id is set and points to a real attempt
        assertThat(result.activeAttemptId()).isNotNull();

        // Assert: attempt row exists and links back
        Integer attemptCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_attempt WHERE id=? AND plan_id=?",
                Integer.class, result.activeAttemptId(), result.id());
        assertThat(attemptCount).isOne();

        // Assert: attempt fields
        String attemptStatus = jdbc.queryForObject(
                "SELECT status FROM ai_task_plan_attempt WHERE id=?",
                String.class, result.activeAttemptId());
        String attemptStage = jdbc.queryForObject(
                "SELECT stage FROM ai_task_plan_attempt WHERE id=?",
                String.class, result.activeAttemptId());
        Integer attemptNo = jdbc.queryForObject(
                "SELECT attempt_no FROM ai_task_plan_attempt WHERE id=?",
                Integer.class, result.activeAttemptId());
        Integer generationSeq = jdbc.queryForObject(
                "SELECT generation_seq FROM ai_task_plan_attempt WHERE id=?",
                Integer.class, result.activeAttemptId());
        UUID attemptCreatedBy = jdbc.queryForObject(
                "SELECT created_by FROM ai_task_plan_attempt WHERE id=?",
                UUID.class, result.activeAttemptId());

        assertThat(attemptStatus).isEqualTo("QUEUED");
        assertThat(attemptStage).isEqualTo("SKELETON");
        assertThat(attemptNo).isEqualTo(1);
        assertThat(generationSeq).isEqualTo(1);
        assertThat(attemptCreatedBy).isEqualTo(userId);
    }

    /**
     * RED-2: When attempt INSERT fails, the entire create() transaction must roll back
     * leaving no orphaned plan row.
     */
    @Test
    void createRollsBackPlanWhenAttemptCreationFails() {
        // Arrange
        UUID userId = insertUser("repo-red-2");
        UUID projectId = insertProject("Red-2 Project", userId);
        insertMember(projectId, userId, "OWNER");

        // Inject a trigger that fails attempt INSERT
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION phase08_fail_attempt_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected attempt failure'; END $$;
                CREATE TRIGGER phase08_fail_attempt_insert
                BEFORE INSERT ON ai_task_plan_attempt
                FOR EACH ROW EXECUTE FUNCTION phase08_fail_attempt_insert()
                """);

        try {
            CreateTaskPlanRequest request = validRequest();

            // Act & Assert: create() must throw (wrapped in tx so rollback is tested)
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.create(projectId, userId, request)))
                    .isInstanceOf(Exception.class);

            // Assert: no plan or attempt rows remain
            Integer planCount = jdbc.queryForObject(
                    "SELECT count(*) FROM ai_task_plan WHERE project_id=?",
                    Integer.class, projectId);
            assertThat(planCount).isZero();

            Integer attemptCount = jdbc.queryForObject(
                    "SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id IN "
                    + "(SELECT id FROM ai_task_plan WHERE project_id=?)",
                    Integer.class, projectId);
            assertThat(attemptCount).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS phase08_fail_attempt_insert ON ai_task_plan_attempt");
            jdbc.execute("DROP FUNCTION IF EXISTS phase08_fail_attempt_insert()");
        }
    }

    /**
     * RED-3: TaskPlanCommandService.create() must return a record with non-null activeAttemptId
     * and orchestrator.dispatch() must be called with that record.
     */
    @Test
    void createReturnsDispatchableRecord() {
        // Arrange
        UUID userId = insertUser("repo-red-3");
        UUID projectId = insertProject("Red-3 Project", userId);
        insertMember(projectId, userId, "OWNER");

        TaskPlanGenerationOrchestrator orchestrator = mock(TaskPlanGenerationOrchestrator.class);
        TaskPlanCommandService service = new TaskPlanCommandService(
                stubGuard(), repository, orchestrator,
                null, jdbc, stubQuota(), stubThrottle(), mock(AuditService.class),
                null, null, null, null, null, null, null, null);

        CreateTaskPlanRequest request = validRequest();

        // Act (wrapped in tx so @Transactional on repository.create() participates)
        TaskPlanRecord[] holder = new TaskPlanRecord[1];
        tx.executeWithoutResult(status -> holder[0] = service.create(projectId, request, userId));
        TaskPlanRecord result = holder[0];

        // Assert: activeAttemptId is set
        assertThat(result).isNotNull();
        assertThat(result.activeAttemptId()).isNotNull();

        // Assert: dispatch was called with the plan that has a non-null activeAttemptId
        verify(orchestrator).dispatch(
                org.mockito.ArgumentMatchers.argThat(plan ->
                        plan.activeAttemptId() != null
                        && plan.id().equals(result.id())
                        && plan.status() == TaskPlanStatus.SKELETON_GENERATING),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(false));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,?,?)
                """, id, "repo-test-" + tag, "test-only-hash", "Repo Test " + tag);
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

    private static CreateTaskPlanRequest validRequest() {
        return new CreateTaskPlanRequest(
                "Red-1 Plan", "Test goal", null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30),
                10, List.of());
    }

    private static ProjectAccessGuard stubGuard() {
        return new ProjectAccessGuard() {
            @Override public ProjectRole requireMember(UUID projectId, UUID userId) { return ProjectRole.OWNER; }
            @Override public void requireAdmin(UUID projectId, UUID userId) {}
            @Override public void requireOwner(UUID projectId, UUID userId) {}
        };
    }

    private static com.shitulelv.aicollab.planning.application.PlanningGenerationQuotaService stubQuota() {
        var qs = mock(com.shitulelv.aicollab.planning.application.PlanningGenerationQuotaService.class);
        return qs;
    }

    private static com.shitulelv.aicollab.planning.application.PlanningAttemptThrottle stubThrottle() {
        var at = mock(com.shitulelv.aicollab.planning.application.PlanningAttemptThrottle.class);
        return at;
    }
}
