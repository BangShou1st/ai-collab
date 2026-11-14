package com.shitulelv.aicollab.work.infrastructure;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=15"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Import(TaskListPaginationPostgresIntegrationTest.SqlCounterConfiguration.class)
class TaskListPaginationPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static final String MINIO_ACCESS_KEY = "task-page-access";
    private static final String MINIO_SECRET_KEY = "task-page-secret-123";

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    @Autowired TaskApplicationService service;
    @Autowired TaskRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskMapperSqlCounter sqlCounter;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Test
    void tenThousandTasksUseTwoMapperQueriesAndStayWithinLocalP95Budget() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedUser(userId, "task-page-10k");
        seedProject(projectId, userId);
        jdbc.update("""
                INSERT INTO project_task(
                    id, project_id, title, status, priority, created_by, created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'Task ' || g, 'TODO', 'MEDIUM', ?,
                       now() - (10000 - g) * interval '1 millisecond',
                       now() - (10000 - g) * interval '1 millisecond'
                FROM generate_series(1, 10000) AS g
                """, projectId, userId);
        List<UUID> firstPageIds = jdbc.queryForList("""
                SELECT id FROM project_task
                WHERE project_id=?
                ORDER BY created_at, id
                LIMIT 200
                """, UUID.class, projectId);
        UUID first = firstPageIds.get(0);
        UUID second = firstPageIds.get(1);
        jdbc.update("INSERT INTO task_dependency(task_id, depends_on_task_id) VALUES (?, ?)",
                first, second);

        for (int index = 0; index < 10; index++) {
            service.list(projectId, null, null, null, 0, 200, userId);
        }
        sqlCounter.reset();
        long started = System.nanoTime();
        var firstPage = service.list(projectId, null, null, null, 0, 200, userId);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(firstPage.items()).hasSize(200);
        assertThat(firstPage.total()).isEqualTo(10_000);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.items().getFirst().dependencyIds()).containsExactly(second);
        assertThat(firstPage.items().getFirst().unfinishedDependencyCount()).isEqualTo(1);
        int perPageMapperSql = sqlCounter.count();
        assertThat(perPageMapperSql).isEqualTo(2);

        List<Long> latencies = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            long start = System.nanoTime();
            service.list(projectId, null, null, null, 0, 200, userId);
            latencies.add((System.nanoTime() - start) / 1_000_000);
        }
        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        System.out.printf("MEASURED task-list-10k firstCallMs=%d p95Ms=%d perPageMapperSql=%d samples=50%n",
                elapsedMillis, p95, perPageMapperSql);
        assertThat(p95).isLessThan(200);

        var beyond = service.list(projectId, null, null, null, 200, 50, userId);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(10_000);
        assertThat(beyond.hasNext()).isFalse();

        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname='public' AND tablename='project_task'
                """, String.class);
        assertThat(indexes).contains("ix_task_project_status_updated");
    }

    @Test
    void dependencyBatchUsesOneQueryPerFiveHundredTaskChunk() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedUser(userId, "task-page-chunk");
        seedProject(projectId, userId);
        jdbc.update("""
                INSERT INTO project_task(id, project_id, title, status, priority, created_by, created_at)
                SELECT gen_random_uuid(), ?, 'Chunk ' || g, 'TODO', 'MEDIUM', ?,
                       now() - (501 - g) * interval '1 millisecond'
                FROM generate_series(1, 501) AS g
                """, projectId, userId);
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM project_task WHERE project_id=? ORDER BY created_at, id
                """, UUID.class, projectId);
        List<Object[]> edges = new ArrayList<>();
        for (int index = 1; index < ids.size(); index++) {
            edges.add(new Object[]{ids.get(index), ids.get(index - 1)});
        }
        jdbc.batchUpdate("INSERT INTO task_dependency(task_id, depends_on_task_id) VALUES (?, ?)", edges);

        sqlCounter.reset();
        var dependencies = repository.dependencyIdsByTask(projectId, ids);
        assertThat(sqlCounter.count()).isEqualTo(2);
        assertThat(dependencies).hasSize(500);
        assertThat(dependencies.get(ids.get(500))).containsExactly(ids.get(499));
    }

    @Test
    void rejectsInvalidPageSizeAndAppliesFilters() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        seedUser(userId, "task-page-filter-owner");
        seedUser(assigneeId, "task-page-filter-assignee");
        seedProject(projectId, userId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                projectId, assigneeId);
        jdbc.update("""
                INSERT INTO milestone(id, project_id, name, status, sort_order, created_by)
                VALUES (?, ?, 'M1', 'ACTIVE', 1, ?)
                """, milestoneId, projectId, userId);
        insertTask(projectId, userId, "A", TaskStatus.TODO, assigneeId, milestoneId);
        insertTask(projectId, userId, "B", TaskStatus.TODO, assigneeId, null);
        insertTask(projectId, userId, "C", TaskStatus.DONE, assigneeId, milestoneId);

        var filtered = service.list(projectId, TaskStatus.TODO, assigneeId, milestoneId, 0, 50, userId);
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items()).extracting(task -> task.title()).containsExactly("A");

        assertThatThrownBy(() -> service.list(projectId, null, null, null, 0, 0, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> service.list(projectId, null, null, null, 0, 201, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> service.list(projectId, null, null, null, -1, 50, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page");
    }

    @Test
    void ordersByCreatedAtThenIdAcrossPages() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID tieA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tieB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID earliest = UUID.fromString("00000000-0000-0000-0000-000000000003");
        seedUser(userId, "task-page-order");
        seedProject(projectId, userId);
        insertTaskAt(projectId, userId, earliest, "earliest", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        insertTaskAt(projectId, userId, tieA, "tie-a", OffsetDateTime.parse("2026-01-01T00:00:01Z"));
        insertTaskAt(projectId, userId, tieB, "tie-b", OffsetDateTime.parse("2026-01-01T00:00:01Z"));

        var firstPage = service.list(projectId, null, null, null, 0, 2, userId);
        var secondPage = service.list(projectId, null, null, null, 1, 2, userId);

        assertThat(firstPage.items()).extracting(task -> task.title())
                .containsExactly("earliest", "tie-a");
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.items()).extracting(task -> task.title()).containsExactly("tie-b");
        assertThat(secondPage.total()).isEqualTo(3);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void countsOnlyUnfinishedDependenciesInTheSameProject() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        seedUser(userId, "task-page-dependencies");
        seedProject(projectId, userId);
        seedProject(otherProjectId, userId);
        UUID target = insertTask(projectId, userId, "target", TaskStatus.TODO, null, null);
        UUID todo = insertTask(projectId, userId, "todo", TaskStatus.TODO, null, null);
        UUID inProgress = insertTask(projectId, userId, "in-progress", TaskStatus.IN_PROGRESS, null, null);
        UUID done = insertTask(projectId, userId, "done", TaskStatus.DONE, null, null);
        UUID canceled = insertTask(projectId, userId, "canceled", TaskStatus.CANCELED, null, null);
        UUID crossProject = insertTask(otherProjectId, userId, "cross-project", TaskStatus.TODO, null, null);
        for (UUID dependencyId : List.of(todo, inProgress, done, canceled, crossProject)) {
            jdbc.update("INSERT INTO task_dependency(task_id, depends_on_task_id) VALUES (?, ?)",
                    target, dependencyId);
        }

        var page = service.list(projectId, null, null, null, 0, 50, userId);
        var targetView = page.items().stream()
                .filter(task -> task.id().equals(target))
                .findFirst()
                .orElseThrow();

        assertThat(targetView.unfinishedDependencyCount()).isEqualTo(2);
        assertThat(targetView.dependencyIds()).containsExactlyInAnyOrder(todo, inProgress, done, canceled);
    }

    private void seedUser(UUID id, String prefix) {
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name, status)
                VALUES (?, ?, '{noop}p', ?, 'ACTIVE')
                """, id, prefix + "-" + id.toString().substring(0, 8), prefix);
    }

    private void seedProject(UUID projectId, UUID ownerId) {
        jdbc.update("INSERT INTO project(id, name, owner_id, created_by) VALUES (?, ?, ?, ?)",
                projectId, "Task page " + projectId, ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
    }

    private UUID insertTask(UUID projectId, UUID userId, String title, TaskStatus status,
                            UUID assigneeId, UUID milestoneId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_task(
                    id, project_id, milestone_id, title, status, priority, assignee_id, created_by)
                VALUES (?, ?, ?, ?, ?, 'MEDIUM', ?, ?)
                """, id, projectId, milestoneId, title, status.name(), assigneeId, userId);
        return id;
    }

    private void insertTaskAt(UUID projectId, UUID userId, UUID id, String title,
                              OffsetDateTime createdAt) {
        jdbc.update("""
                INSERT INTO project_task(
                    id, project_id, title, status, priority, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'TODO', 'MEDIUM', ?, ?, ?)
                """, id, projectId, title, userId, createdAt, createdAt);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCounterConfiguration {
        @Bean
        TaskMapperSqlCounter taskMapperSqlCounter() {
            return new TaskMapperSqlCounter();
        }
    }

    @Intercepts(@Signature(
            type = Executor.class,
            method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class TaskMapperSqlCounter implements Interceptor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith(".TaskMapper.listPageScoped")
                    || statement.getId().endsWith(".TaskMapper.listDependencyEdges")) {
                calls.incrementAndGet();
            }
            return invocation.proceed();
        }

        int count() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
