package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.project.application.view.DashboardTaskStatsView;
import com.shitulelv.aicollab.project.application.view.DashboardView;
import com.shitulelv.aicollab.work.api.dto.BatchUpdateTasksRequest;
import com.shitulelv.aicollab.work.api.dto.CreateMilestoneRequest;
import com.shitulelv.aicollab.work.api.dto.CreateTaskRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateMilestoneRequest;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.FixedHostPortGenericContainer;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 C4：看板缓存真实集成测试矩阵（真实 PostgreSQL 17 + Redis 7）。
 *
 * <p>覆盖 C-T02～C-T05、C-T06～C-T11、C-T12/C-T17（并发）、C-T13（跨实例共享失效的
 * 等价验证）、C-T14/C-T15（Redis 停机降级与恢复）、C-T16（TTL 边界）、C-T18（脏数据）。
 * C-T01/C-T19 由 {@code ProjectDashboardQueryBenchmarkPostgresIntegrationTest} 承担，
 * C-T20 由完整 {@code mvnw verify} 与远端 CI 承担。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        // 停机降级场景的延迟上界依赖短命令超时；其余用例 Redis 在线，500ms 足够
        "spring.data.redis.timeout=500ms",
        // 熔断冷却窗口用短值（2s），保证恢复用例可在有界时间内完成半开试探
        "dashboard.cache.failure-cooldown=2s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardCachePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "cache-access";
    private static final String MINIO_SECRET_KEY = "cache-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /**
     * Redis 使用固定主机端口映射：C-T15 需要在容器重启（模拟停机恢复）后保持
     * localhost:16379 不变，否则应用侧启动期固定的连接属性会指向失效端口。
     */
    @Container
    static final GenericContainer<?> REDIS =
            new FixedHostPortGenericContainer<>("redis:7-alpine")
                    .withFixedExposedPort(16379, 6379);

    private static final UUID OWNER = UUID.fromString("62000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("62000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDER = UUID.fromString("62000000-0000-0000-0000-000000000003");
    private static final UUID PROJECT_A = UUID.fromString("63000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_B = UUID.fromString("63000000-0000-0000-0000-000000000002");

    @Autowired ProjectDashboardQueryService dashboard;
    @Autowired DashboardCacheService dashboardCache;
    @Autowired TaskApplicationService taskService;
    @Autowired MilestoneApplicationService milestoneService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    private final AtomicReference<UUID> seededTaskA = new AtomicReference<>();
    private final AtomicReference<UUID> seededMilestoneA = new AtomicReference<>();

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
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 16379);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        insertUser(OWNER, "owner", "缓存所有者");
        insertUser(MEMBER, "member", "缓存成员");
        insertUser(OUTSIDER, "outsider", "外部用户");
        insertProject(PROJECT_A, "缓存项目甲", OWNER);
        insertProject(PROJECT_B, "缓存项目乙", OUTSIDER);
        insertMember(PROJECT_A, OWNER, "OWNER");
        insertMember(PROJECT_A, MEMBER, "MEMBER");
        insertMember(PROJECT_B, OUTSIDER, "OWNER");

        UUID milestoneA = UUID.randomUUID();
        jdbc.update("""
                insert into milestone(id, project_id, name, target_date, status, created_by)
                values (?, ?, '缓存里程碑', ?, 'ACTIVE', ?)
                """, milestoneA, PROJECT_A, LocalDate.now(ZoneOffset.UTC).plusDays(7), OWNER);
        seededMilestoneA.set(milestoneA);

        UUID taskA = UUID.randomUUID();
        insertTask(taskA, PROJECT_A, "缓存任务甲", "TODO");
        insertTask(UUID.randomUUID(), PROJECT_A, "缓存任务乙", "DONE");
        insertTask(UUID.randomUUID(), PROJECT_B, "乙项目任务", "TODO");
        seededTaskA.set(taskA);
    }

    private String statsKey(UUID projectId) {
        return DashboardCacheService.STATS_KEY_PREFIX + projectId;
    }

    private String milestonesKey(UUID projectId) {
        return DashboardCacheService.MILESTONES_KEY_PREFIX + projectId;
    }

    /** C-T02 / C-T03：未命中回源并回填；命中返回缓存数据（直改数据库也不影响已缓存结果）。 */
    @Test
    void missFillsThenHitServesCachedData() {
        DashboardView first = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNotNull();
        assertThat(first.tasks().total()).isEqualTo(2);

        // 绕过应用层直改数据库：命中路径必须返回缓存里的旧统计，证明确实在读缓存
        jdbc.update("UPDATE project_task SET status='CANCELED' WHERE id=?", seededTaskA.get());
        DashboardView cached = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(cached.tasks().total()).isEqualTo(2);
        assertThat(cached.tasks().canceled()).isZero();

        // 失效后重新回源：读到的才是数据库新状态
        dashboardCache.evictNow(PROJECT_A);
        DashboardView refreshed = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(refreshed.tasks().canceled()).isEqualTo(1);
    }

    /** C-T04：不同项目缓存互不串用。 */
    @Test
    void projectsDoNotShareCacheEntries() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        dashboard.getDashboard(PROJECT_B, OUTSIDER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();
        assertThat(redis.opsForValue().get(statsKey(PROJECT_B))).isNotNull();

        DashboardView viewA = dashboard.getDashboard(PROJECT_A, MEMBER);
        DashboardView viewB = dashboard.getDashboard(PROJECT_B, OUTSIDER);
        assertThat(viewA.project().id()).isEqualTo(PROJECT_A);
        assertThat(viewB.project().id()).isEqualTo(PROJECT_B);
        assertThat(viewA.tasks().total()).isEqualTo(2);
        assertThat(viewB.tasks().total()).isEqualTo(1);

        // 清掉 A 的缓存不能影响 B 的缓存
        dashboardCache.evictNow(PROJECT_A);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_B))).isNotNull();
    }

    /** C-T05：权限校验先于缓存读取，缓存命中不能绕过 requireMember。 */
    @Test
    void permissionCheckIsNeverBypassedByCache() {
        // 先把 PROJECT_A 的缓存预热，再让无权限用户访问同一项目
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();

        assertThatThrownBy(() -> dashboard.getDashboard(PROJECT_A, OUTSIDER))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> dashboard.getDashboard(PROJECT_A, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    /** C-T06 / C-T07 / C-T09：任务创建、更新、删除、批量更新提交后缓存失效。 */
    @Test
    void taskWritesInvalidateCacheAfterCommit() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();

        taskService.create(PROJECT_A,
                new CreateTaskRequest("失效验证任务", null, null, null,
                        TaskStatus.TODO, TaskPriority.HIGH, null, null, null),
                OWNER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).tasks().total()).isEqualTo(3);

        UUID taskId = seededTaskA.get();
        taskService.update(PROJECT_A, taskId,
                new UpdateTaskRequest("缓存任务甲", null, null, null,
                        TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, null, null, null,
                        taskService.get(PROJECT_A, taskId, OWNER).version()),
                OWNER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).tasks().inProgress()).isEqualTo(1);

        taskService.batchUpdate(PROJECT_A,
                new BatchUpdateTasksRequest(List.of(
                        new BatchUpdateTasksRequest.TaskUpdateItem(taskId, null, TaskStatus.BLOCKED,
                                TaskPriority.MEDIUM, null,
                                taskService.get(PROJECT_A, taskId, OWNER).version()))),
                OWNER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).tasks().blocked()).isEqualTo(1);

        taskService.delete(PROJECT_A, taskId, OWNER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).tasks().total()).isEqualTo(2);
    }

    /** C-T08：里程碑创建、更新、删除提交后缓存失效。 */
    @Test
    void milestoneWritesInvalidateCacheAfterCommit() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNotNull();

        UUID milestoneId = milestoneService.create(PROJECT_A,
                new CreateMilestoneRequest("新增里程碑", null, null, null,
                        LocalDate.now(ZoneOffset.UTC).plusDays(14), MilestoneStatus.PLANNED, 0),
                OWNER).id();
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).milestones()).hasSize(2);

        milestoneService.update(PROJECT_A, milestoneId,
                new UpdateMilestoneRequest("新增里程碑", null, null, null,
                        LocalDate.now(ZoneOffset.UTC).plusDays(14), MilestoneStatus.COMPLETED, 0, 0),
                OWNER);
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNull();

        milestoneService.delete(PROJECT_A, milestoneId, OWNER);
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNull();
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).milestones()).hasSize(1);
    }

    /** C-T10 / C-T11：事务回滚不失效；提交前缓存原样保留，提交后才删除。 */
    @Test
    void evictionFollowsTransactionOutcome() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();

        // 回滚：注册了失效但事务回滚，缓存必须原样保留（afterCommit 不触发）
        transactionTemplate.executeWithoutResult(status -> {
            dashboardCache.evictAfterCommit(PROJECT_A);
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNotNull();

        // 提交：提交前缓存仍在（C-T11 顺序正确），提交后被删除
        AtomicReference<Boolean> keysPresentAtBeforeCommit = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    keysPresentAtBeforeCommit.set(redis.hasKey(statsKey(PROJECT_A)));
                }
            });
            dashboardCache.evictAfterCommit(PROJECT_A);
        });
        assertThat(keysPresentAtBeforeCommit.get()).isTrue();
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_A))).isNull();
    }

    /** C-T12 / C-T17：并发读写与热点失效下无异常、最终读到一致的新值。 */
    @Test
    void concurrentReadersAndWritersRemainCorrect() throws Exception {
        dashboard.getDashboard(PROJECT_A, MEMBER);

        int readers = 8;
        int writes = 10;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> readerFutures = new java.util.ArrayList<>();
            for (int r = 0; r < readers; r++) {
                readerFutures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 15; i++) {
                        DashboardView view = dashboard.getDashboard(PROJECT_A, MEMBER);
                        // 读到的统计永远是自洽的：未取消任务数 = 总数 - 取消数
                        assertThat(view.tasks().total() - view.tasks().canceled())
                                .isBetween(1, 2);
                    }
                    return null;
                }));
            }
            Future<Void> writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < writes; i++) {
                    TaskStatus target = i % 2 == 0 ? TaskStatus.IN_PROGRESS : TaskStatus.TODO;
                    taskService.update(PROJECT_A, seededTaskA.get(),
                            new UpdateTaskRequest("缓存任务甲", null, null, null,
                                    target, TaskPriority.MEDIUM, null, null, null,
                                    taskService.get(PROJECT_A, seededTaskA.get(), OWNER).version()),
                            OWNER);
                }
                return null;
            });

            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            for (Future<Void> future : readerFutures) {
                future.get(60, TimeUnit.SECONDS);
            }

            // 写完并全部失效后，最终读必须反映最终状态（TODO；乙项目任务为 DONE 不计入）
            DashboardView finalView = dashboard.getDashboard(PROJECT_A, MEMBER);
            assertThat(finalView.tasks().inProgress()).isZero();
            assertThat(finalView.tasks().todo()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * C-T13：无进程内本地缓存，所有实例共享 Redis；另一实例的 DEL 对本实例立即可见。
     * 用一条全新连接（模拟另一实例）删除共享键来验证跨实例失效生效。
     */
    @Test
    void invalidationIsSharedThroughRedisAcrossInstances() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();

        // 模拟“另一个实例”用独立 ObjectProvider 执行同样的失效逻辑（共享同一 Redis）
        DashboardCacheService otherInstanceCache = new DashboardCacheService(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public StringRedisTemplate getObject() {
                        return redis;
                    }
                    @Override
                    public StringRedisTemplate getIfAvailable() {
                        return redis;
                    }
                },
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                true, 90, 60, java.time.Duration.ofSeconds(30));
        otherInstanceCache.evictNow(PROJECT_A);

        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNull();
        // 本实例下一次读必须回源数据库（不会看到已删除的旧缓存）
        jdbc.update("UPDATE project_task SET status='CANCELED' WHERE id=?", seededTaskA.get());
        assertThat(dashboard.getDashboard(PROJECT_A, MEMBER).tasks().canceled()).isEqualTo(1);
    }

    /**
     * C-T14 / C-T15：Redis 停机时熔断短路降级直读数据库（调用延迟有明确上界）；
     * 恢复后熔断半开试探成功，缓存自动继续工作。
     */
    @Test
    @Order(99)
    void redisOutageFallsBackToDatabaseAndRecovers() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(redis.opsForValue().get(statsKey(PROJECT_A))).isNotNull();

        jdbc.update("UPDATE project_task SET status='IN_PROGRESS' WHERE id=?", seededTaskA.get());
        dashboardCache.evictNow(PROJECT_A);

        REDIS.stop();
        DashboardView duringOutage;
        try {
            // 首次调用触发熔断：getOrLoadStats 的读操作按命令超时（500ms）失败后熔断打开，
            // 同一请求内其余 Redis 操作全部短路；整体上界远小于修复前 4x60s
            long firstStart = System.nanoTime();
            duringOutage = dashboard.getDashboard(PROJECT_A, MEMBER);
            long firstCallMillis = (System.nanoTime() - firstStart) / 1_000_000;
            assertThat(duringOutage.tasks().inProgress()).isEqualTo(1);
            assertThat(firstCallMillis).as("停机期间首次调用（触发熔断）应 < 10s").isLessThan(10_000L);

            // 熔断已打开：后续调用请求内短路 + 冷却期跳过，单次应在 1s 量级内
            for (int i = 0; i < 3; i++) {
                long quickStart = System.nanoTime();
                DashboardView quick = dashboard.getDashboard(PROJECT_A, MEMBER);
                long quickMillis = (System.nanoTime() - quickStart) / 1_000_000;
                assertThat(quick.tasks().inProgress()).isEqualTo(1);
                assertThat(quickMillis).as("熔断生效后第 %d 次调用应 < 1s", i + 1).isLessThan(1_000L);
            }
        } finally {
            REDIS.start();
        }

        // Redis 恢复后：等待 Lettuce 连接看门狗重连；熔断冷却（2s）到期后半开试探
        // 成功即自动恢复缓存读写
        awaitRedisReconnected();
        DashboardView afterRecovery = awaitCacheAutoRecovered();
        assertThat(afterRecovery.tasks().inProgress()).isEqualTo(1);
        String cachedJson = redis.opsForValue().get(statsKey(PROJECT_A));
        assertThat(cachedJson).isNotNull();
        // 回填值为信封格式，负载里才是视图 JSON
        assertThat(readEnvelope(cachedJson).data()).contains("\"inProgress\":1");

        // 可观测性：熔断状态机的 open / half_open / recover 与操作级 error / skip 均已落表
        assertThat(counterValue("open")).as("熔断打开指标").isGreaterThanOrEqualTo(1);
        assertThat(counterValue("half_open")).as("半开试探指标").isGreaterThanOrEqualTo(1);
        assertThat(counterValue("recover")).as("熔断恢复指标").isGreaterThanOrEqualTo(1);
        assertThat(counterValue("skip")).as("熔断跳过指标").isGreaterThanOrEqualTo(3);
        assertThat(counterValue("error")).as("Redis 操作失败指标").isGreaterThanOrEqualTo(1);
    }

    /** 熔断冷却到期后半开试探成功、缓存自动回填；有界轮询等待（最长 20s）。 */
    private DashboardView awaitCacheAutoRecovered() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                dashboardCache.evictNow(PROJECT_A);
                DashboardView view = dashboard.getDashboard(PROJECT_A, MEMBER);
                if (redis.opsForValue().get(statsKey(PROJECT_A)) != null) {
                    return view;
                }
            } catch (RuntimeException attempt) {
                last = attempt;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("缓存未在预期时间内自动恢复", last);
    }

    /** 读取 dashboard.cache.operations{result=...} 计数；未注册视为 0。 */
    private double counterValue(String result) {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find("dashboard.cache.operations")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 容器重启后 Lettuce 共享连接需要经看门狗重连，做有界重试等待。 */
    private void awaitRedisReconnected() {
        RuntimeException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                redis.opsForValue().get(statsKey(PROJECT_A));
                return;
            } catch (RuntimeException attempt) {
                last = attempt;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 Redis 恢复被中断", e);
                }
            }
        }
        throw new IllegalStateException("Redis 未在预期时间内恢复连接", last);
    }

    /** C-T16：缓存键带有限 TTL（逻辑 90s ±10% 抖动 + 300s 重建宽限 = 物理 381~399s），不是无限期。 */
    @Test
    void cacheKeysCarryBoundedTtl() {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        Long statsTtl = redis.getExpire(statsKey(PROJECT_A));
        Long milestonesTtl = redis.getExpire(milestonesKey(PROJECT_A));
        assertThat(statsTtl).isNotNull();
        assertThat(statsTtl).isBetween(381L, 399L);
        assertThat(milestonesTtl).isNotNull();
        assertThat(milestonesTtl).isBetween(381L, 399L);
    }

    /** C-T18：缓存值损坏（非法 JSON / 结构不兼容）时返回数据库新值且不抛异常。 */
    @Test
    void corruptedCacheValuesAreDiscarded() throws Exception {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        redis.opsForValue().set(statsKey(PROJECT_A), "{not-json-at-all");
        redis.opsForValue().set(milestonesKey(PROJECT_A), "[{\"unexpected\":true}]");

        jdbc.update("UPDATE project_task SET status='BLOCKED' WHERE id=?", seededTaskA.get());
        DashboardView view = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(view.tasks().blocked()).isEqualTo(1);

        // 脏键应被删除并已用新值回填（回填格式为治理后的信封，负载里是视图 JSON）
        DashboardCacheService.CacheEnvelope statsEnvelope =
                readEnvelope(redis.opsForValue().get(statsKey(PROJECT_A)));
        assertThat(statsEnvelope.nullMarker()).isFalse();
        assertThat(statsEnvelope.data()).contains("\"blocked\":1");

        DashboardCacheService.CacheEnvelope milestonesEnvelope =
                readEnvelope(redis.opsForValue().get(milestonesKey(PROJECT_A)));
        assertThat(objectMapper.readValue(milestonesEnvelope.data(),
                new com.fasterxml.jackson.core.type.TypeReference<List<com.shitulelv.aicollab.project.application.view.DashboardMilestoneProgressView>>() { }))
                .hasSize(1);
    }

    /** 治理新增：空项目里程碑列表缓存为短 TTL 空标记，创建里程碑提交后标记被 evict，不被挡 60s。 */
    @Test
    void emptyMilestoneMarkerIsEvictedByMilestoneWrite() throws Exception {
        // PROJECT_B 无里程碑：回源空列表 → 空标记（短 TTL），命中不查库
        dashboard.getDashboard(PROJECT_B, OUTSIDER);
        String markerJson = redis.opsForValue().get(milestonesKey(PROJECT_B));
        assertThat(markerJson).isNotNull();
        DashboardCacheService.CacheEnvelope marker = readEnvelope(markerJson);
        assertThat(marker.nullMarker()).isTrue();
        Long markerTtl = redis.getExpire(milestonesKey(PROJECT_B));
        assertThat(markerTtl).as("空标记 TTL 应为 60s 量级短值").isBetween(1L, 60L);

        // 空标记命中：再次读仍为空（不回源）
        assertThat(dashboard.getDashboard(PROJECT_B, OUTSIDER).milestones()).isEmpty();

        // 写路径提交后 evict 删除空标记（与真实值同 key），新数据立即可见
        milestoneService.create(PROJECT_B,
                new CreateMilestoneRequest("乙项目首里程碑", null, null, null,
                        LocalDate.now(ZoneOffset.UTC).plusDays(7), MilestoneStatus.PLANNED, 0),
                OUTSIDER);
        assertThat(redis.opsForValue().get(milestonesKey(PROJECT_B)))
                .as("提交后空标记应随同 key 一起被 DEL").isNull();
        assertThat(dashboard.getDashboard(PROJECT_B, OUTSIDER).milestones()).hasSize(1);
    }

    /** 治理新增：逻辑过期命中返回旧值 + 异步重建回填新值（物理 TTL 内自愈）。 */
    @Test
    void logicallyExpiredEnvelopeServesStaleThenRebuilds() throws Exception {
        dashboard.getDashboard(PROJECT_A, MEMBER);
        DashboardCacheService.CacheEnvelope fresh = readEnvelope(redis.opsForValue().get(statsKey(PROJECT_A)));
        assertThat(fresh.data()).contains("\"inProgress\":0");

        // 手工构造"逻辑已过期、物理仍存活"的信封（模拟 TTL 到点但物理未删）
        DashboardCacheService.CacheEnvelope expired = new DashboardCacheService.CacheEnvelope(
                fresh.data(), false, System.currentTimeMillis() - 1_000);
        redis.opsForValue().set(statsKey(PROJECT_A),
                objectMapper.writeValueAsString(expired), Duration.ofMinutes(10));

        // 数据库已变更，但读路径命中逻辑过期：先返回旧值，不阻塞等待重建
        jdbc.update("UPDATE project_task SET status='IN_PROGRESS' WHERE id=?", seededTaskA.get());
        DashboardView stale = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(stale.tasks().inProgress()).as("逻辑过期应返回旧值").isZero();

        // 异步重建（虚拟线程）有界轮询等待回填新值
        awaitStatsEnvelopeDataContains("\"inProgress\":1");
        DashboardView refreshed = dashboard.getDashboard(PROJECT_A, MEMBER);
        assertThat(refreshed.tasks().inProgress()).isEqualTo(1);

        // 重建回填的信封携带新的逻辑过期时间戳
        DashboardCacheService.CacheEnvelope rebuilt = readEnvelope(redis.opsForValue().get(statsKey(PROJECT_A)));
        assertThat(rebuilt.logicalExpireAtMillis()).isGreaterThan(System.currentTimeMillis());
    }

    /** 有界轮询（最长 10s）等待 stats 信封负载包含期望片段（异步重建完成）。 */
    private void awaitStatsEnvelopeDataContains(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                String json = redis.opsForValue().get(statsKey(PROJECT_A));
                if (json != null && readEnvelope(json).data() != null
                        && readEnvelope(json).data().contains(fragment)) {
                    return;
                }
            } catch (RuntimeException attempt) {
                last = attempt;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("异步重建未在预期时间内回填新值", last);
    }

    /** 解析缓存信封；解析失败直接失败测试（包装为运行时异常，便于轮询助手统一捕获）。 */
    private DashboardCacheService.CacheEnvelope readEnvelope(String json) {
        try {
            return objectMapper.readValue(json, DashboardCacheService.CacheEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("缓存信封解析失败: " + json, e);
        }
    }

    // ---------- 种子数据助手（与既有集成测试保持一致的列集合） ----------

    private void insertUser(UUID id, String username, String displayName) {
        jdbc.update("""
                insert into app_user(id, username, password_hash, display_name)
                values (?, ?, 'cache-test-hash', ?)
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

    private void insertTask(UUID id, UUID projectId, String title, String status) {
        jdbc.update("""
                insert into project_task(
                    id, project_id, milestone_id, title, status, priority,
                    assignee_id, due_date, created_by, updated_at)
                values (?, ?, ?, ?, ?, 'MEDIUM', ?, ?, ?, ?)
                """, id, projectId, seededMilestoneA.get(), title, status, MEMBER,
                LocalDate.now(ZoneOffset.UTC), MEMBER, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
