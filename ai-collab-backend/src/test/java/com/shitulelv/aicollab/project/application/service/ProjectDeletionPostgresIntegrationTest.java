package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanCommandService;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class ProjectDeletionPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "deletion-access";
    private static final String MINIO_SECRET_KEY = "deletion-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final UUID OWNER = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("81000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("83000000-0000-0000-0000-000000000001");
    private static final UUID RESULT = UUID.fromString("84000000-0000-0000-0000-000000000001");
    private static final UUID MEMORY = UUID.fromString("85000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("86000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_VERSION = UUID.fromString("87000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_CONFIRMATION = UUID.fromString("88000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_MILESTONE = UUID.fromString("89000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_TASK = UUID.fromString("8a000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    @Autowired ProjectApplicationService projects;
    @Autowired TaskPlanCommandService taskPlanCommands;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "deletion-owner");
        insertUser(MEMBER, "deletion-member");
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?, '待删除项目', '含 Job/候选/记忆/审计', ?, ?, DATE '2026-11-01',
                        DATE '2026-12-31')
                """, PROJECT, OWNER, OWNER);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                PROJECT, OWNER);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'MEMBER')",
                PROJECT, MEMBER);
        jdbc.update("""
                INSERT INTO ai_job(id,project_id,job_type,status,payload,created_by)
                VALUES (?,?,'MEMORY_EXTRACTION','SUCCEEDED','{}'::jsonb,?)
                """, JOB, PROJECT, OWNER);
        jdbc.update("""
                INSERT INTO ai_job_attempt(job_id,attempt_no,status,lease_epoch,started_at,finished_at)
                VALUES (?,1,'SUCCEEDED',1,now(),now())
                """, JOB);
        jdbc.update("""
                INSERT INTO ai_job_result(id,job_id,attempt_no,result_kind,dedupe_key,payload,
                                          review_status)
                VALUES (?,?,1,'MEMORY_CANDIDATE',?,'{"kind":"DECISION"}'::jsonb,'ACCEPTED')
                """, RESULT, JOB, "a".repeat(64));
        jdbc.update("""
                INSERT INTO agent_memory(id,project_id,type,title,content,source_type,source_id,
                                         status,created_by,updated_by)
                VALUES (?,?,'DECISION','已发布记忆','内容','AI_JOB_RESULT',?,'ACTIVE',?,?)
                """, MEMORY, PROJECT, RESULT, OWNER, OWNER);
        jdbc.update("""
                INSERT INTO audit_log(project_id,user_id,action,entity_type,entity_id,request_id)
                VALUES (?,?,'AGENT_MEMORY_CANDIDATE_APPROVED','AI_JOB_RESULT',?,?)
                """, PROJECT, OWNER, RESULT, UUID.randomUUID());
    }

    @Test
    void ownerDeletesProjectWithJobCandidateMemoryAndAuditReferences() {
        projects.delete(PROJECT, OWNER);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE id=?", Integer.class, PROJECT)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job WHERE id=?", Integer.class, JOB)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE id=?", Integer.class, RESULT)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_memory WHERE id=?", Integer.class, MEMORY)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='PROJECT_DELETED' AND project_id IS NULL AND entity_id=?
                """, Integer.class, PROJECT)).isOne();
    }

    @Test
    void nonOwnerCannotDeleteAndAuditOrBusinessRowsAreUntouched() {
        assertThatThrownBy(() -> projects.delete(PROJECT, MEMBER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.PROJECT_OWNER_REQUIRED));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE id=?", Integer.class, PROJECT)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job WHERE id=?", Integer.class, JOB)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_memory WHERE id=?", Integer.class, MEMORY)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='PROJECT_DELETED' AND entity_id=?
                """, Integer.class, PROJECT)).isZero();
    }

    @Test
    void ownerDeletesProjectWithConfirmedPlanAndGeneratedArtifacts() {
        seedConfirmedPlanGraph();

        projects.delete(PROJECT, OWNER);

        assertThat(count("project", PROJECT)).isZero();
        assertThat(count("ai_task_plan", PLAN)).isZero();
        assertThat(count("ai_task_plan_version", PLAN_VERSION)).isZero();
        assertThat(count("ai_task_plan_confirmation", PLAN_CONFIRMATION)).isZero();
        assertThat(count("project_task", PLAN_TASK)).isZero();
        assertThat(count("milestone", PLAN_MILESTONE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='PROJECT_DELETED_WITH_CONFIRMED_PLANS'
                   AND project_id IS NULL AND entity_id=?
                   AND detail->>'confirmedPlanCount'='1'
                   AND detail->>'terminalStatus'='CANCELED'
                   AND detail->>'releaseReason'='OWNER_PROJECT_DELETE'
                """, Integer.class, PROJECT)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='PROJECT_DELETED' AND project_id IS NULL AND entity_id=?
                """, Integer.class, PROJECT)).isOne();
    }

    @Test
    void confirmedPlanCannotBeDeletedThroughNormalPlanCommandOrRawDelete() {
        seedConfirmedPlanGraph();

        assertThatThrownBy(() -> taskPlanCommands.delete(PROJECT, PLAN, OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.TASK_PLAN_STATE_CONFLICT));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ai_task_plan WHERE id=?", PLAN))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_task_plan WHERE id=?", String.class, PLAN))
                .isEqualTo("CONFIRMED");
        assertThat(count("project_task", PLAN_TASK)).isOne();
        assertThat(count("milestone", PLAN_MILESTONE)).isOne();
    }

    @Test
    void nonOwnerCannotDeleteProjectWithConfirmedPlan() {
        seedConfirmedPlanGraph();

        assertThatThrownBy(() -> projects.delete(PROJECT, MEMBER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.PROJECT_OWNER_REQUIRED));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_task_plan WHERE id=?", String.class, PLAN))
                .isEqualTo("CONFIRMED");
        assertThat(count("project_task", PLAN_TASK)).isOne();
        assertThat(count("milestone", PLAN_MILESTONE)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='PROJECT_DELETED_WITH_CONFIRMED_PLANS' AND entity_id=?
                """, Integer.class, PROJECT)).isZero();
    }

    @Test
    void documentConstraintStillWinsBeforeConfirmedPlansAreReleased() {
        seedConfirmedPlanGraph();
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,uploaded_by)
                VALUES (?,?,'保留文档','keep.txt','text/plain',4,?,'UPLOADED',0,?)
                """, UUID.fromString("8b000000-0000-0000-0000-000000000001"), PROJECT,
                "acceptance/keep-" + PROJECT + ".txt", OWNER);

        assertThatThrownBy(() -> projects.delete(PROJECT, OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.PROJECT_DOCUMENTS_EXIST));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_task_plan WHERE id=?", String.class, PLAN))
                .isEqualTo("CONFIRMED");
        assertThat(count("project_document",
                UUID.fromString("8b000000-0000-0000-0000-000000000001"))).isOne();
    }

    private void seedConfirmedPlanGraph() {
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,
                  max_task_count,status,latest_version_no,created_by,confirmed_by,confirmed_at)
                VALUES (?,?,'已确认规划','删除项目时释放触发器',DATE '2026-11-01',
                        DATE '2026-11-30',10,'CONFIRMED',1,?,?,now())
                """, PLAN, PROJECT, OWNER, OWNER);
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'AI_COMPLETE',1,?)
                """, PLAN_VERSION, PLAN, OWNER);
        jdbc.update("""
                UPDATE ai_task_plan
                   SET latest_version_id=?, confirmed_version_id=?
                 WHERE id=?
                """, PLAN_VERSION, PLAN_VERSION, PLAN);
        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(id,project_id,plan_id,version_id,
                  idempotency_key,request_hash,status,created_by,completed_at)
                VALUES (?,?,?,?,?,?,'SUCCESS',?,now())
                """, PLAN_CONFIRMATION, PROJECT, PLAN, PLAN_VERSION, UUID.randomUUID(),
                "1".repeat(64), OWNER);
        jdbc.update("""
                INSERT INTO milestone(id,project_id,name,status,sort_order,created_by,
                  source_plan_id,source_plan_version_id,source_plan_milestone_key)
                VALUES (?,?,'规划里程碑','PLANNED',0,?,?,?,?)
                """, PLAN_MILESTONE, PROJECT, OWNER, PLAN, PLAN_VERSION, "m1");
        jdbc.update("""
                INSERT INTO project_task(id,project_id,milestone_id,title,status,priority,
                  created_by,source_plan_id,source_plan_version_id,source_plan_task_key)
                VALUES (?,?,?,'规划任务','TODO','MEDIUM',?,?,?,?)
                """, PLAN_TASK, PROJECT, PLAN_MILESTONE, OWNER, PLAN, PLAN_VERSION, "t1");
    }

    private int count(String table, UUID id) {
        String sql = switch (table) {
            case "project" -> "SELECT count(*) FROM project WHERE id=?";
            case "ai_task_plan" -> "SELECT count(*) FROM ai_task_plan WHERE id=?";
            case "ai_task_plan_version" -> "SELECT count(*) FROM ai_task_plan_version WHERE id=?";
            case "ai_task_plan_confirmation" -> "SELECT count(*) FROM ai_task_plan_confirmation WHERE id=?";
            case "project_task" -> "SELECT count(*) FROM project_task WHERE id=?";
            case "milestone" -> "SELECT count(*) FROM milestone WHERE id=?";
            case "project_document" -> "SELECT count(*) FROM project_document WHERE id=?";
            default -> throw new IllegalArgumentException("unexpected table: " + table);
        };
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,'test-only-hash',?)
                """, id, username, username);
    }
}
