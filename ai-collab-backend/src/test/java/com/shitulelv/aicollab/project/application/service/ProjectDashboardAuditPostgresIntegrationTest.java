package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.view.DashboardView;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
class ProjectDashboardAuditPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "dashboard-access";
    private static final String MINIO_SECRET_KEY = "dashboard-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID PROJECT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID MILESTONE_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID AUDIT_A_OLD = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID AUDIT_A_NEW = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID AUDIT_B = UUID.fromString("50000000-0000-0000-0000-000000000003");

    private static final List<UUID> PROJECT_A_TASKS = List.of(
            UUID.fromString("60000000-0000-0000-0000-000000000001"),
            UUID.fromString("60000000-0000-0000-0000-000000000002"),
            UUID.fromString("60000000-0000-0000-0000-000000000003"),
            UUID.fromString("60000000-0000-0000-0000-000000000004"),
            UUID.fromString("60000000-0000-0000-0000-000000000005"));
    private static final UUID PROJECT_B_TASK =
            UUID.fromString("60000000-0000-0000-0000-000000000006");

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

    @Autowired ProjectDashboardQueryService dashboard;
    @Autowired AuditLogQueryService audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTwoProjects() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "owner", "所有者");
        insertUser(ADMIN, "admin", "管理员");
        insertUser(MEMBER, "member", "成员");
        insertUser(OUTSIDER, "outsider", "外部用户");
        insertProject(PROJECT_A, "甲项目", OWNER);
        insertProject(PROJECT_B, "乙项目", OUTSIDER);
        insertMember(PROJECT_A, OWNER, "OWNER");
        insertMember(PROJECT_A, ADMIN, "ADMIN");
        insertMember(PROJECT_A, MEMBER, "MEMBER");
        insertMember(PROJECT_B, OUTSIDER, "OWNER");

        jdbc.update("""
                insert into milestone(id, project_id, name, target_date, status, created_by)
                values (?, ?, '完成核心功能', ?, 'ACTIVE', ?)
                """, MILESTONE_A, PROJECT_A, LocalDate.now().minusDays(1), OWNER);

        insertTask(PROJECT_A_TASKS.get(0), PROJECT_A, MILESTONE_A, "待办任务", "TODO",
                LocalDate.now().minusDays(1), OWNER, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        insertTask(PROJECT_A_TASKS.get(1), PROJECT_A, MILESTONE_A, "进行中任务", "IN_PROGRESS",
                LocalDate.now().plusDays(1), ADMIN, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(4));
        insertTask(PROJECT_A_TASKS.get(2), PROJECT_A, MILESTONE_A, "阻塞任务", "BLOCKED",
                null, MEMBER, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        insertTask(PROJECT_A_TASKS.get(3), PROJECT_A, MILESTONE_A, "完成任务", "DONE",
                LocalDate.now().minusDays(2), OWNER, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        insertTask(PROJECT_A_TASKS.get(4), PROJECT_A, MILESTONE_A, "取消任务", "CANCELED",
                LocalDate.now().minusDays(3), OWNER, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        insertTask(PROJECT_B_TASK, PROJECT_B, null, "乙项目任务", "DONE",
                LocalDate.now(), OUTSIDER, OffsetDateTime.now(ZoneOffset.UTC));

        insertDocument(DOCUMENT_A, PROJECT_A, OWNER, "甲需求.pdf");
        insertDocument(DOCUMENT_B, PROJECT_B, OUTSIDER, "乙需求.pdf");
        insertAudit(AUDIT_A_OLD, PROJECT_A, OWNER, "TASK_CREATED", "TASK",
                PROJECT_A_TASKS.get(0), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        insertAudit(AUDIT_A_NEW, PROJECT_A, ADMIN, "TASK_STATUS_CHANGED", "TASK",
                PROJECT_A_TASKS.get(1), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        insertAudit(AUDIT_B, PROJECT_B, OUTSIDER, "TASK_CREATED", "TASK",
                PROJECT_B_TASK, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void dashboardUsesDocumentedCountsAndNeverLeaksOtherProjectRows() {
        DashboardView view = dashboard.getDashboard(PROJECT_A, MEMBER);

        assertThat(view.project().memberCount()).isEqualTo(3);
        assertThat(view.tasks().total()).isEqualTo(5);
        assertThat(view.tasks().todo()).isEqualTo(1);
        assertThat(view.tasks().inProgress()).isEqualTo(1);
        assertThat(view.tasks().blocked()).isEqualTo(1);
        assertThat(view.tasks().done()).isEqualTo(1);
        assertThat(view.tasks().canceled()).isEqualTo(1);
        assertThat(view.tasks().overdue()).isEqualTo(1);
        assertThat(view.tasks().completionRate()).isEqualTo(0.250d);
        assertThat(view.recentTasks())
                .extracting(item -> item.id())
                .containsExactlyInAnyOrderElementsOf(PROJECT_A_TASKS)
                .doesNotContain(PROJECT_B_TASK);
        assertThat(view.recentDocuments())
                .extracting(item -> item.id())
                .containsExactly(DOCUMENT_A);
        assertThat(view.recentActivities())
                .extracting(item -> item.id())
                .containsExactly(AUDIT_A_NEW, AUDIT_A_OLD)
                .doesNotContain(AUDIT_B);
    }

    @Test
    void dashboardRejectsNonMemberWithoutRevealingProjectData() {
        assertThatThrownBy(() -> dashboard.getDashboard(PROJECT_A, OUTSIDER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
    }

    @Test
    void auditAllowsOwnerAndAdminButRejectsMemberAndKeepsStableOrder() {
        assertThat(audit.page(PROJECT_A, OWNER, 1, 20).items())
                .extracting(item -> item.id())
                .containsExactly(AUDIT_A_NEW, AUDIT_A_OLD);
        assertThat(audit.page(PROJECT_A, ADMIN, 1, 1).items())
                .extracting(item -> item.id())
                .containsExactly(AUDIT_A_NEW);
        assertThat(audit.page(PROJECT_A, ADMIN, 1, 1).total()).isEqualTo(2);
        assertThatThrownBy(() -> audit.page(PROJECT_A, MEMBER, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ADMIN_REQUIRED));
    }

    private void insertUser(UUID id, String username, String displayName) {
        jdbc.update("""
                insert into app_user(id, username, password_hash, display_name)
                values (?, ?, 'test-hash', ?)
                """, id, username, displayName);
    }

    private void insertProject(UUID id, String name, UUID ownerId) {
        jdbc.update("""
                insert into project(id, name, owner_id, created_by)
                values (?, ?, ?, ?)
                """, id, name, ownerId, ownerId);
    }

    private void insertMember(UUID projectId, UUID userId, String role) {
        jdbc.update("""
                insert into project_member(project_id, user_id, role)
                values (?, ?, ?)
                """, projectId, userId, role);
    }

    private void insertTask(
            UUID id,
            UUID projectId,
            UUID milestoneId,
            String title,
            String status,
            LocalDate dueDate,
            UUID assigneeId,
            OffsetDateTime updatedAt) {
        jdbc.update("""
                insert into project_task(
                    id, project_id, milestone_id, title, status, priority,
                    assignee_id, due_date, created_by, updated_at)
                values (?, ?, ?, ?, ?, 'MEDIUM', ?, ?, ?, ?)
                """, id, projectId, milestoneId, title, status, assigneeId, dueDate, assigneeId, updatedAt);
    }

    private void insertDocument(UUID id, UUID projectId, UUID uploaderId, String filename) {
        jdbc.update("""
                insert into project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, uploaded_by)
                values (?, ?, ?, ?, 'application/pdf', 10, ?, 'READY', ?)
                """, id, projectId, filename, filename, projectId + "/" + id, uploaderId);
    }

    private void insertAudit(
            UUID id,
            UUID projectId,
            UUID userId,
            String action,
            String entityType,
            UUID entityId,
            OffsetDateTime createdAt) {
        jdbc.update("""
                insert into audit_log(
                    id, project_id, user_id, action, entity_type, entity_id, request_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, userId, action, entityType, entityId, UUID.randomUUID(), createdAt);
    }
}
