package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,'test-only-hash',?)
                """, id, username, username);
    }
}
