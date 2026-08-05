package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ProjectOwnershipTransferPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "ownership-access";
    private static final String MINIO_SECRET_KEY = "ownership-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final UUID OWNER = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID PROJECT = UUID.fromString("72000000-0000-0000-0000-000000000001");

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

    @Autowired ProjectMemberApplicationService service;
    @Autowired ProjectAccessGuard access;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedProject() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "owner");
        insertUser(ADMIN, "admin");
        insertUser(MEMBER, "member");
        insertUser(OUTSIDER, "outsider");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?, '转让测试', ?, ?)",
                PROJECT, OWNER, OWNER);
        insertMember(OWNER, "OWNER");
        insertMember(ADMIN, "ADMIN");
        insertMember(MEMBER, "MEMBER");
    }

    @Test
    void transfersToMemberAndChangesPermissionsAndWritesAudit() {
        service.transferOwnership(PROJECT, MEMBER, OWNER);

        assertOwnership(MEMBER);
        assertThat(role(OWNER)).isEqualTo("MEMBER");
        assertThatCode(() -> access.requireOwner(PROJECT, MEMBER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireOwner(PROJECT, OWNER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_OWNER_REQUIRED));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE project_id=? AND action='PROJECT_OWNERSHIP_TRANSFERRED'
                  AND user_id=? AND entity_id=?
                """, Integer.class, PROJECT, OWNER, PROJECT)).isEqualTo(1);
    }

    @Test
    void transfersToAdminWithoutViolatingSingleOwnerIndex() {
        service.transferOwnership(PROJECT, ADMIN, OWNER);

        assertOwnership(ADMIN);
        assertThat(role(OWNER)).isEqualTo("MEMBER");
    }

    @Test
    void rejectsTransferToSelfWithStableBusinessError() {
        assertBusinessError(() -> service.transferOwnership(PROJECT, OWNER, OWNER),
                "PROJECT_OWNERSHIP_SELF_TRANSFER");
        assertOwnership(OWNER);
    }

    @Test
    void rejectsTargetWhoIsNoLongerAMemberWithStableBusinessError() {
        assertBusinessError(() -> service.transferOwnership(PROJECT, OUTSIDER, OWNER),
                "PROJECT_OWNERSHIP_TARGET_NOT_MEMBER");
        assertOwnership(OWNER);
    }

    @Test
    void rejectsNonOwnerOperator() {
        assertBusinessError(() -> service.transferOwnership(PROJECT, MEMBER, ADMIN),
                "PROJECT_OWNER_REQUIRED");
        assertOwnership(OWNER);
    }

    @Test
    void concurrentTransfersProduceExactlyOneNewOwnerAndOneAudit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> memberAttempt = executor.submit(() -> transferAfter(start, MEMBER));
            Future<String> adminAttempt = executor.submit(() -> transferAfter(start, ADMIN));
            start.countDown();

            List<String> outcomes = List.of(memberAttempt.get(), adminAttempt.get());
            assertThat(outcomes).contains("SUCCESS");
            assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> value.equals("PROJECT_OWNER_REQUIRED")
                    || value.equals("PROJECT_OWNERSHIP_CONFLICT")).count()).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_member WHERE project_id=? AND role='OWNER'",
                Integer.class, PROJECT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id=? AND action='PROJECT_OWNERSHIP_TRANSFERRED'",
                Integer.class, PROJECT)).isEqualTo(1);
        UUID projectOwner = jdbc.queryForObject("SELECT owner_id FROM project WHERE id=?", UUID.class, PROJECT);
        UUID membershipOwner = jdbc.queryForObject(
                "SELECT user_id FROM project_member WHERE project_id=? AND role='OWNER'", UUID.class, PROJECT);
        assertThat(projectOwner).isEqualTo(membershipOwner);
    }

    @Test
    void rollsBackEveryOwnershipChangeWhenTargetPromotionAffectsNoRows() {
        jdbc.execute("""
                CREATE FUNCTION suppress_owner_promotion() RETURNS trigger AS $$
                BEGIN
                  IF OLD.user_id = '71000000-0000-0000-0000-000000000003'::uuid
                     AND NEW.role = 'OWNER' THEN
                    RETURN NULL;
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER suppress_owner_promotion
                BEFORE UPDATE OF role ON project_member
                FOR EACH ROW EXECUTE FUNCTION suppress_owner_promotion()
                """);
        try {
            assertBusinessError(() -> service.transferOwnership(PROJECT, MEMBER, OWNER),
                    "PROJECT_OWNERSHIP_TARGET_NOT_MEMBER");
        } finally {
            jdbc.execute("DROP TRIGGER suppress_owner_promotion ON project_member");
            jdbc.execute("DROP FUNCTION suppress_owner_promotion()");
        }

        assertOwnership(OWNER);
        assertThat(role(MEMBER)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id=? AND action='PROJECT_OWNERSHIP_TRANSFERRED'",
                Integer.class, PROJECT)).isZero();
    }

    private String transferAfter(CountDownLatch start, UUID target) throws InterruptedException {
        start.await();
        try {
            service.transferOwnership(PROJECT, target, OWNER);
            return "SUCCESS";
        } catch (BusinessException error) {
            return error.getErrorCode().name();
        }
    }

    private void assertOwnership(UUID expectedOwner) {
        assertThat(jdbc.queryForObject("SELECT owner_id FROM project WHERE id=?", UUID.class, PROJECT))
                .isEqualTo(expectedOwner);
        assertThat(role(expectedOwner)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_member WHERE project_id=? AND role='OWNER'",
                Integer.class, PROJECT)).isEqualTo(1);
    }

    private String role(UUID userId) {
        return jdbc.queryForObject(
                "SELECT role FROM project_member WHERE project_id=? AND user_id=?",
                String.class, PROJECT, userId);
    }

    private void assertBusinessError(ThrowingCall call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode().name()).isEqualTo(errorCode));
    }

    private void insertUser(UUID id, String username) {
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'hash',?)",
                id, username, username);
    }

    private void insertMember(UUID userId, String role) {
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,?)",
                PROJECT, userId, role);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
