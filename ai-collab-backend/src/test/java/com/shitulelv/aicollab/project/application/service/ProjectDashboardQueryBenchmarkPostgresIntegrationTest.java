package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView;
import com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView;
import com.shitulelv.aicollab.project.application.view.DashboardView;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectDashboardMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 C1：看板缓存候选查询的真实基准。
 *
 * <p>目的：在真实 PostgreSQL 17 + Redis 7 上测量 {@code countTasksByStatus} 与
 * {@code listMilestoneProgress}（阶段 C 唯一允许的缓存候选）在 1k / 10k / 100k
 * 任务规模下的真实查询成本，并与 Redis 缓存命中路径（GET + JSON 反序列化）的
 * 真实成本对比，为缓存实施决策提供数据。分布为均匀随机，不构造有利缓存收益的
 * 极端数据。</p>
 *
 * <p>这是测量型基准而非回归断言，默认不参与 {@code mvn verify}/CI（避免每次构建
 * 都灌 10 万任务）。需要重新测量时显式开启：
 * {@code ./mvnw test -Dtest=ProjectDashboardQueryBenchmarkPostgresIntegrationTest -Ddashboard.benchmark=true}。</p>
 */
@EnabledIfSystemProperty(named = "dashboard.benchmark", matches = "true")
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
class ProjectDashboardQueryBenchmarkPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "bench-access";
    private static final String MINIO_SECRET_KEY = "bench-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static final UUID OWNER = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final int MILESTONE_COUNT = 20;
    private static final String[] STATUSES = {"TODO", "TODO", "IN_PROGRESS", "BLOCKED", "DONE", "DONE", "CANCELED"};

    private static final int[] SCALES = {1_000, 10_000, 100_000};
    private static final int WARMUP_RUNS = 5;
    private static final int TIMED_RUNS = 40;

    @Autowired ProjectDashboardQueryService dashboard;
    @Autowired ProjectDashboardMapper dashboardMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    private final List<long[]> statsTimingsByScale = new ArrayList<>();
    private final List<long[]> milestonesTimingsByScale = new ArrayList<>();
    private final List<long[]> redisHitTimingsByScale = new ArrayList<>();

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void printSummary() {
        // 汇总在 @Test 内已直接打印；此处保留容器关闭前的钩子位。
    }

    @Test
    void benchmarkCacheableQueriesAcrossScales() throws Exception {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "owner", "基准所有者");
        insertUser(MEMBER, "member", "基准成员");
        insertProject(PROJECT, "基准项目", OWNER);
        insertMember(PROJECT, MEMBER, "MEMBER");
        for (int m = 1; m <= MILESTONE_COUNT; m++) {
            insertMilestone(UUID.nameUUIDFromBytes(("bench-milestone-" + m).getBytes()), m);
        }

        long inserted = 0;
        for (int scaleIndex = 0; scaleIndex < SCALES.length; scaleIndex++) {
            int target = SCALES[scaleIndex];
            insertTasksBatch(inserted, target);
            inserted = target;

            // 热身：填充缓冲区与连接池
            for (int i = 0; i < WARMUP_RUNS; i++) {
                dashboardMapper.countTasksByStatus(PROJECT);
                dashboardMapper.listMilestoneProgress(PROJECT);
            }

            long[] statsTimes = new long[TIMED_RUNS];
            long[] milestoneTimes = new long[TIMED_RUNS];
            for (int i = 0; i < TIMED_RUNS; i++) {
                long t0 = System.nanoTime();
                DashboardTaskStatsView stats = dashboardMapper.countTasksByStatus(PROJECT);
                long t1 = System.nanoTime();
                List<DashboardMilestoneProgressView> milestones = dashboardMapper.listMilestoneProgress(PROJECT);
                long t2 = System.nanoTime();
                statsTimes[i] = t1 - t0;
                milestoneTimes[i] = t2 - t1;
                assertThat(stats.total()).isEqualTo(target);
                assertThat(milestones).hasSize(MILESTONE_COUNT);
            }

            // 模拟缓存命中路径：真实 Redis GET + Jackson 反序列化（与 C2 实现同构）
            DashboardTaskStatsView statsSnapshot = dashboardMapper.countTasksByStatus(PROJECT);
            List<DashboardMilestoneProgressView> milestoneSnapshot = dashboardMapper.listMilestoneProgress(PROJECT);
            String statsJson = objectMapper.writeValueAsString(statsSnapshot);
            String milestonesJson = objectMapper.writeValueAsString(milestoneSnapshot);
            String statsKey = "ai-collab:dashboard:stats:v1:" + PROJECT;
            String milestonesKey = "ai-collab:dashboard:milestones:v1:" + PROJECT;
            redis.opsForValue().set(statsKey, statsJson, Duration.ofSeconds(60));
            redis.opsForValue().set(milestonesKey, milestonesJson, Duration.ofSeconds(60));
            for (int i = 0; i < WARMUP_RUNS; i++) {
                objectMapper.readValue(redis.opsForValue().get(statsKey), DashboardTaskStatsView.class);
                objectMapper.readValue(redis.opsForValue().get(milestonesKey),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, DashboardMilestoneProgressView.class));
            }
            long[] redisTimes = new long[TIMED_RUNS];
            for (int i = 0; i < TIMED_RUNS; i++) {
                long t0 = System.nanoTime();
                DashboardTaskStatsView statsHit =
                        objectMapper.readValue(redis.opsForValue().get(statsKey), DashboardTaskStatsView.class);
                @SuppressWarnings("unchecked")
                List<DashboardMilestoneProgressView> milestoneHit = (List<DashboardMilestoneProgressView>)
                        objectMapper.readValue(redis.opsForValue().get(milestonesKey),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, DashboardMilestoneProgressView.class));
                long t1 = System.nanoTime();
                redisTimes[i] = t1 - t0;
                assertThat(statsHit.total()).isEqualTo(target);
                assertThat(milestoneHit).hasSize(MILESTONE_COUNT);
            }

            statsTimingsByScale.add(statsTimes);
            milestonesTimingsByScale.add(milestoneTimes);
            redisHitTimingsByScale.add(redisTimes);

            printRow(scaleIndex, target, statsTimes, milestoneTimes, redisTimes);
        }

        // 完整 getDashboard（未缓存全链路）参考值：100k 规模
        for (int i = 0; i < WARMUP_RUNS; i++) {
            dashboard.getDashboard(PROJECT, MEMBER);
        }
        long fullMin = Long.MAX_VALUE;
        long fullMax = Long.MIN_VALUE;
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            DashboardView view = dashboard.getDashboard(PROJECT, MEMBER);
            fullMin = Math.min(fullMin, System.nanoTime() - t0);
            fullMax = Math.max(fullMax, System.nanoTime() - t0);
            assertThat(view.tasks().total()).isEqualTo(100_000);
        }
        System.out.printf(Locale.ROOT,
                "[BENCH] full getDashboard @100k tasks: min=%.2fms max=%.2fms%n",
                fullMin / 1e6, fullMax / 1e6);
    }

    private void printRow(int scaleIndex, int scale, long[] stats, long[] milestones, long[] redis) {
        System.out.printf(Locale.ROOT,
                "[BENCH] scale=%d tasks | stats P50=%.2fms P95=%.2fms | milestones P50=%.2fms P95=%.2fms"
                        + " | redis-hit(json roundtrip) P50=%.2fms P95=%.2fms%n",
                scale,
                percentile(stats, 50) / 1e6, percentile(stats, 95) / 1e6,
                percentile(milestones, 50) / 1e6, percentile(milestones, 95) / 1e6,
                percentile(redis, 50) / 1e6, percentile(redis, 95) / 1e6);
    }

    private static long percentile(long[] values, int p) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private void insertTasksBatch(long fromInclusive, long toExclusive) {
        String sql = """
                insert into project_task(
                    id, project_id, milestone_id, title, status, priority,
                    assignee_id, due_date, created_by, updated_at)
                values (%s)
                """.formatted(placeholders(10));
        int batchSize = 500;
        List<Object[]> rows = new ArrayList<>(batchSize);
        for (long i = fromInclusive; i < toExclusive; i++) {
            UUID taskId = UUID.nameUUIDFromBytes(("bench-task-" + i).getBytes());
            UUID milestoneId = UUID.nameUUIDFromBytes(("bench-milestone-" + (i % MILESTONE_COUNT + 1)).getBytes());
            String status = STATUSES[(int) (i % STATUSES.length)];
            LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays((i % 60) - 30);
            OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(i % 86_400);
            rows.add(new Object[]{taskId, PROJECT, milestoneId, "基准任务 " + i, status,
                    "MEDIUM", MEMBER, dueDate, MEMBER, updatedAt});
            if (rows.size() == batchSize) {
                jdbc.batchUpdate(sql, rows);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private void insertUser(UUID id, String username, String displayName) {
        jdbc.update("""
                insert into app_user(id, username, password_hash, display_name)
                values (?, ?, 'bench-not-a-real-hash', ?)
                """, id, username, displayName);
    }

    private void insertProject(UUID id, String name, UUID ownerId) {
        jdbc.update("""
                insert into project(id, name, owner_id, created_by)
                values (?, ?, ?, ?)
                """, id, name, ownerId, ownerId);
    }

    private void insertMember(UUID projectId, UUID userId, String role) {
        jdbc.update("insert into project_member(project_id, user_id, role) values (?, ?, ?)",
                projectId, userId, role);
    }

    private void insertMilestone(UUID id, int seq) {
        jdbc.update("""
                insert into milestone(id, project_id, name, target_date, status, created_by)
                values (?, ?, ?, ?, 'ACTIVE', ?)
                """, id, PROJECT, "基准里程碑 " + seq, LocalDate.now(ZoneOffset.UTC).plusDays(seq), OWNER);
    }
}
