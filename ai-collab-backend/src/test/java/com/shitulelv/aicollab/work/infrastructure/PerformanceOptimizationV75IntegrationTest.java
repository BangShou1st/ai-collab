package com.shitulelv.aicollab.work.infrastructure;

import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.home.application.view.HomeTaskView;
import com.shitulelv.aicollab.home.infrastructure.mapper.HomeMapper;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView;
import com.shitulelv.aicollab.project.application.view.DashboardProjectView;
import com.shitulelv.aicollab.project.application.view.DashboardRecentDocumentView;
import com.shitulelv.aicollab.project.application.view.DashboardRecentTaskView;
import com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectDashboardMapper;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.work.infrastructure.mapper.TaskMapper;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
class PerformanceOptimizationV75IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static final String MINIO_ACCESS_KEY = "v75-access-key";
    private static final String MINIO_SECRET_KEY = "v75-secret-key-123";

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
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

    @Autowired HomeMapper homeMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired ProjectDashboardMapper dashboardMapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Test
    void verifyV75IndexesExistInDatabase() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname",
                String.class
        );

        assertThat(indexes).contains(
                "idx_task_assignee_active_due",
                "idx_task_project_updated",
                "idx_task_project_created",
                "idx_task_project_milestone",
                "idx_task_dependency_depends",
                "idx_document_project_recent"
        );
    }

    @Test
    void testHomeMapperMyTasksSemanticsAndSorting() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        // 1. Insert users
        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name, status) VALUES (?, 'user1', '{noop}p', 'User One', 'ACTIVE')", u1);
        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name, status) VALUES (?, 'user2', '{noop}p', 'User Two', 'ACTIVE')", u2);

        // 2. Insert projects
        jdbc.update("INSERT INTO project (id, name, owner_id, created_by) VALUES (?, 'Project Alpha', ?, ?)", p1, u1, u1);
        jdbc.update("INSERT INTO project (id, name, owner_id, created_by) VALUES (?, 'Project Beta', ?, ?)", p2, u2, u2);

        // 3. User1 is member of Project Alpha, NOT member of Project Beta
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'OWNER')", p1, u1);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'OWNER')", p2, u2);

        LocalDate today = LocalDate.now();

        // 4. Tasks for User1 in Project Alpha (active tasks with various due dates)
        UUID t1 = UUID.randomUUID(); // overdue
        UUID t2 = UUID.randomUUID(); // due today
        UUID t3 = UUID.randomUUID(); // due future
        UUID t4 = UUID.randomUUID(); // null due date
        UUID t5 = UUID.randomUUID(); // DONE task (should be filtered out)
        UUID t6 = UUID.randomUUID(); // Task in Project Beta assigned to User1 (should be filtered out because user1 is not member)

        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, 'Task Overdue', 'TODO', 'HIGH', ?, ?, ?, now() - interval '1 hour')", t1, p1, u1, today.minusDays(3), u1);
        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, 'Task Today', 'IN_PROGRESS', 'URGENT', ?, ?, ?, now() - interval '2 hour')", t2, p1, u1, today, u1);
        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, 'Task Future', 'BLOCKED', 'MEDIUM', ?, ?, ?, now() - interval '3 hour')", t3, p1, u1, today.plusDays(5), u1);
        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, 'Task No Due', 'TODO', 'LOW', ?, NULL, ?, now())", t4, p1, u1, u1);
        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by) VALUES (?, ?, 'Task Done', 'DONE', 'HIGH', ?, ?, ?)", t5, p1, u1, today.minusDays(5), u1);
        jdbc.update("INSERT INTO project_task (id, project_id, title, status, priority, assignee_id, due_date, created_by) VALUES (?, ?, 'Task Beta', 'TODO', 'HIGH', ?, ?, ?)", t6, p2, u1, today.minusDays(1), u2);

        List<HomeTaskView> tasks = homeMapper.myTasks(u1, 10);

        // Exactly 4 tasks returned (t1, t2, t3, t4)
        assertThat(tasks).hasSize(4);

        // Sorting: due_date ASC NULLS LAST
        // 1st: t1 (overdue)
        // 2nd: t2 (today)
        // 3rd: t3 (future)
        // 4th: t4 (null due date)
        assertThat(tasks.get(0).id()).isEqualTo(t1);
        assertThat(tasks.get(0).dueDate()).isEqualTo(today.minusDays(3));

        assertThat(tasks.get(1).id()).isEqualTo(t2);
        assertThat(tasks.get(1).dueDate()).isEqualTo(today);

        assertThat(tasks.get(2).id()).isEqualTo(t3);
        assertThat(tasks.get(2).dueDate()).isEqualTo(today.plusDays(5));

        assertThat(tasks.get(3).id()).isEqualTo(t4);
        assertThat(tasks.get(3).dueDate()).isNull();

        // Check limit parameter
        List<HomeTaskView> limitedTasks = homeMapper.myTasks(u1, 2);
        assertThat(limitedTasks).hasSize(2);
        assertThat(limitedTasks.get(0).id()).isEqualTo(t1);
        assertThat(limitedTasks.get(1).id()).isEqualTo(t2);
    }

    @Test
    void testTaskMapperListScopedAndDependencyCounts() {
        UUID u = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        UUID m = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name, status) VALUES (?, 'work_user', '{noop}p', 'Worker', 'ACTIVE')", u);
        jdbc.update("INSERT INTO project (id, name, owner_id, created_by) VALUES (?, 'Work Project', ?, ?)", p, u, u);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'OWNER')", p, u);
        jdbc.update("INSERT INTO milestone (id, project_id, name, status, sort_order, created_by) VALUES (?, ?, 'Sprint 1', 'ACTIVE', 1, ?)", m, p, u);

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();

        // t1 created first, t2 second, t3 third
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, created_by, created_at) VALUES (?, ?, ?, 'Task 1', 'TODO', 'MEDIUM', ?, ?, now() - interval '3 hours')", t1, p, m, u, u);
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, created_by, created_at) VALUES (?, ?, ?, 'Task 2', 'DONE', 'HIGH', ?, ?, now() - interval '2 hours')", t2, p, m, u, u);
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, created_by, created_at) VALUES (?, ?, ?, 'Task 3', 'TODO', 'LOW', ?, ?, now() - interval '1 hour')", t3, p, m, u, u);

        // t3 depends on t1 (TODO) and t2 (DONE)
        jdbc.update("INSERT INTO task_dependency (task_id, depends_on_task_id) VALUES (?, ?)", t3, t1);
        jdbc.update("INSERT INTO task_dependency (task_id, depends_on_task_id) VALUES (?, ?)", t3, t2);

        // List all tasks scoped to project
        List<TaskEntity> list = taskMapper.listScoped(p, null, null, null);
        assertThat(list).hasSize(3);

        // Ordered by created_at: t1, t2, t3
        assertThat(list.get(0).getId()).isEqualTo(t1);
        assertThat(list.get(1).getId()).isEqualTo(t2);
        assertThat(list.get(2).getId()).isEqualTo(t3);

        // Check unfinished dependency counts:
        // t1: 0 dependencies
        // t2: 0 dependencies
        // t3: depends on t1 (TODO - unfinished) and t2 (DONE - finished) -> exactly 1 unfinished!
        assertThat(list.get(0).getUnfinishedDependencyCount()).isZero();
        assertThat(list.get(1).getUnfinishedDependencyCount()).isZero();
        assertThat(list.get(2).getUnfinishedDependencyCount()).isEqualTo(1);

        // Test scoped find
        var foundT3 = taskMapper.findScoped(p, t3);
        assertThat(foundT3).isPresent();
        assertThat(foundT3.get().getUnfinishedDependencyCount()).isEqualTo(1);
        assertThat(foundT3.get().getMilestoneName()).isEqualTo("Sprint 1");
        assertThat(foundT3.get().getAssigneeDisplayName()).isEqualTo("Worker");

        // Test filtered by status
        List<TaskEntity> todoTasks = taskMapper.listScoped(p, TaskStatus.TODO, null, null);
        assertThat(todoTasks).hasSize(2).extracting(TaskEntity::getId).containsExactly(t1, t3);
    }

    @Test
    void testProjectDashboardMapperQueries() {
        UUID u = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name, status) VALUES (?, 'dash_user', '{noop}p', 'Dash User', 'ACTIVE')", u);
        jdbc.update("INSERT INTO project (id, name, description, owner_id, created_by, status) VALUES (?, 'Dashboard Proj', 'Test Dash', ?, ?, 'ACTIVE')", p, u, u);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'OWNER')", p, u);

        LocalDate today = LocalDate.now();

        jdbc.update("INSERT INTO milestone (id, project_id, name, status, sort_order, target_date, created_by) VALUES (?, ?, 'M1', 'ACTIVE', 1, ?, ?)", m1, p, today.minusDays(2), u);
        jdbc.update("INSERT INTO milestone (id, project_id, name, status, sort_order, target_date, created_by) VALUES (?, ?, 'M2', 'PLANNED', 2, ?, ?)", m2, p, today.plusDays(10), u);

        // Tasks in M1: 1 DONE, 1 TODO (overdue)
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        // Task in M2: 1 IN_PROGRESS
        UUID t3 = UUID.randomUUID();
        // Task without milestone: 1 CANCELED
        UUID t4 = UUID.randomUUID();

        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, ?, 'T1 Done', 'DONE', 'MEDIUM', ?, ?, ?, now() - interval '5 min')", t1, p, m1, u, today.minusDays(5), u);
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, ?, 'T2 Overdue', 'TODO', 'HIGH', ?, ?, ?, now() - interval '10 min')", t2, p, m1, u, today.minusDays(1), u);
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, ?, 'T3 Active', 'IN_PROGRESS', 'URGENT', ?, ?, ?, now() - interval '1 min')", t3, p, m2, u, today.plusDays(3), u);
        jdbc.update("INSERT INTO project_task (id, project_id, milestone_id, title, status, priority, assignee_id, due_date, created_by, updated_at) VALUES (?, ?, NULL, 'T4 Cancel', 'CANCELED', 'LOW', ?, NULL, ?, now())", t4, p, u, u);

        // Document for project
        UUID docId = UUID.randomUUID();
        jdbc.update("INSERT INTO project_document (id, project_id, display_name, original_filename, mime_type, size_bytes, object_key, status, uploaded_by) VALUES (?, ?, 'Spec.pdf', 'Spec.pdf', 'application/pdf', 2048, 'docs/spec.pdf', 'READY', ?)", docId, p, u);

        // 1. findProjectInfo
        DashboardProjectView info = dashboardMapper.findProjectInfo(p, u);
        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("Dashboard Proj");
        assertThat(info.currentUserRole()).isEqualTo(ProjectRole.OWNER);
        assertThat(info.memberCount()).isEqualTo(1L);

        // 2. countTasksByStatus
        DashboardTaskStatsView stats = dashboardMapper.countTasksByStatus(p);
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.todo()).isEqualTo(1);
        assertThat(stats.inProgress()).isEqualTo(1);
        assertThat(stats.blocked()).isZero();
        assertThat(stats.done()).isEqualTo(1);
        assertThat(stats.canceled()).isEqualTo(1);
        assertThat(stats.overdue()).isEqualTo(1);
        // completionRate = done / (total - canceled) = 1 / 3 = 0.333
        assertThat(stats.completionRate()).isEqualTo(0.333);

        // 3. listMilestoneProgress
        List<DashboardMilestoneProgressView> milestones = dashboardMapper.listMilestoneProgress(p);
        assertThat(milestones).hasSize(2);
        DashboardMilestoneProgressView m1Progress = milestones.get(0);
        assertThat(m1Progress.name()).isEqualTo("M1");
        assertThat(m1Progress.totalTasks()).isEqualTo(2);
        assertThat(m1Progress.completedTasks()).isEqualTo(1);
        assertThat(m1Progress.completionRate()).isEqualTo(0.5);
        assertThat(m1Progress.overdue()).isTrue(); // target_date was in past

        DashboardMilestoneProgressView m2Progress = milestones.get(1);
        assertThat(m2Progress.name()).isEqualTo("M2");
        assertThat(m2Progress.totalTasks()).isEqualTo(1);
        assertThat(m2Progress.completedTasks()).isZero();
        assertThat(m2Progress.overdue()).isFalse();

        // 4. listRecentTasks
        List<DashboardRecentTaskView> recentTasks = dashboardMapper.listRecentTasks(p, 2);
        assertThat(recentTasks).hasSize(2);
        // ordered by updated_at DESC: t4 (now), t3 (now - 1 min)
        assertThat(recentTasks.get(0).id()).isEqualTo(t4);
        assertThat(recentTasks.get(1).id()).isEqualTo(t3);

        // 5. listRecentDocuments
        List<DashboardRecentDocumentView> recentDocs = dashboardMapper.listRecentDocuments(p, 5);
        assertThat(recentDocs).hasSize(1);
        assertThat(recentDocs.get(0).id()).isEqualTo(docId);
        assertThat(recentDocs.get(0).originalFilename()).isEqualTo("Spec.pdf");
        assertThat(recentDocs.get(0).uploaderDisplayName()).isEqualTo("Dash User");
    }
}
