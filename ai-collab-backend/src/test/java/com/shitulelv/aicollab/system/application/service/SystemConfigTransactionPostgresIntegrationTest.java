package com.shitulelv.aicollab.system.application.service;

import com.shitulelv.aicollab.agent.application.AgentDynamicScheduleRegistrar;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.system.api.dto.UpdateSystemConfigRequest;
import com.shitulelv.aicollab.system.application.view.SystemConfigView;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.infrastructure.repository.SystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * A1 动态配置事务一致性集成验证：真实 PostgreSQL 上验证提交/回滚可见性。
 * Redis 深化 P2：广播通道由 Pub/Sub 升级为 Stream，广播断言改为 Stream 条目与
 * 消费者组（每实例一组 = 广播语义）视角。
 */
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemConfigTransactionPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "syscfg-access";
    private static final String MINIO_SECRET_KEY = "syscfg-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final UUID ADMIN = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final String STREAM = SystemConfigService.CONFIG_SYNC_STREAM_KEY;

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

    @Autowired SystemConfigService service;
    @Autowired SystemConfigRepository repository;
    @Autowired SystemConfigSync configSync;
    @Autowired StringRedisTemplate stringRedisTemplate;
    @Autowired StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamContainer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AgentDynamicScheduleRegistrar dynamicScheduleRegistrar;
    @MockitoBean com.shitulelv.aicollab.agent.application.AgentWorkerConcurrencyManager concurrencyManager;

    private final CopyOnWriteArrayList<Long> recordedStreamLengths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetConfiguration() {
        // 应用自带的 Stream 消费容器停掉：避免异步消费在断言间隙回源数据库，
        // 干扰"本机应用失败保持版本 1"（t10）等对即时状态的断言；广播送达由
        // 本测试手工创建的"实例"消费者组断言。
        streamContainer.stop();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, display_name, system_admin)
                VALUES (?, 'syscfg-admin', 'not-a-real-hash', '系统管理员', true)
                ON CONFLICT (id) DO UPDATE SET system_admin = true, status = 'ACTIVE'
                """, ADMIN);
        jdbc.update("""
                UPDATE system_configuration
                SET agent_enabled=false, chat_enabled=false, worker_concurrency=2,
                    worker_delay_ms=1000, schedule_delay_ms=30000, system_maintenance_mode=false,
                    system_maintenance_message='', public_registration_enabled=true,
                    default_chat_model='', default_agent_model='',
                    rag_retrieval_threshold=0.55, rag_max_sources=12, version=1, updated_by=NULL
                WHERE id=?
                """, SystemConfig.DEFAULT_ID);
        jdbc.update("DELETE FROM system_config_audit WHERE config_version > 1");
        service.init();
        recordedStreamLengths.clear();
    }

    @AfterEach
    void cleanupStream() {
        // 清理流与测试建组，保证各用例从干净状态开始；Redis 已停机的用例跳过清理
        try {
            stringRedisTemplate.delete(STREAM);
        } catch (Exception ignored) {
            // Redis 停机用例（a1_t05）之后无需清理
        }
    }

    @Test
    @Order(1)
    void a1_t01_t04_appliesRuntimeAndBroadcastsOnlyAfterCommit() {
        // 两个手工消费者组模拟两个实例：消费者组语义是每组独立收到全量消息（广播）
        createGroup("it-a");
        createGroup("it-b");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AtomicReference<Long> runtimeVersionInsideTx = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            service.updateConfig(ADMIN, request(1L, true, 4, 1500L, 20000L), "admin");
            runtimeVersionInsideTx.set(service.getActiveConfig().version());
        });

        assertThat(runtimeVersionInsideTx.get()).isEqualTo(1L);

        // 事务提交后 Stream 中出现 version=2 条目（广播仅在提交后发生；过滤建组占位条目）
        List<MapRecord<String, Object, Object>> entries = stringRedisTemplate.opsForStream()
                .range(STREAM, org.springframework.data.domain.Range.closed("-", "+"));
        assertThat(entries)
                .filteredOn(e -> "2".equals(String.valueOf(e.getValue().get("version"))))
                .hasSize(1);

        // 两个"实例"的消费者组都能读到同一条广播（Stream 弥补 Pub/Sub 掉线即丢的关键能力）
        assertThat(readFromGroup("it-a")).containsExactly("2");
        assertThat(readFromGroup("it-b")).containsExactly("2");

        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);
        assertThat(service.getWorkerDelayMs()).isEqualTo(1500L);
        assertThat(dbVersion()).isEqualTo(2L);
    }

    @Test
    @Order(2)
    void a1_t02_t03_rollbackKeepsRuntimeAndDbAndSilencesBroadcast() {
        createGroup("it-a");
        long streamLengthBefore = streamLength();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            service.updateConfig(ADMIN, request(1L, true, 3, 2500L, 25000L), "admin");
            status.setRollbackOnly();
        });

        assertThat(service.getActiveConfig().version()).isEqualTo(1L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(2);
        assertThat(dbVersion()).isEqualTo(1L);
        assertThat(auditCount()).isZero();

        // 回滚不广播：Stream 无新条目
        assertThat(streamLength()).isEqualTo(streamLengthBefore);
    }

    @Test
    @Order(3)
    void a1_t08_restoreConfigHasSameTransactionGuarantees() {
        service.updateConfig(ADMIN, request(1L, true, 4, 1500L, 20000L), "admin");
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);

        UUID auditId = jdbc.queryForObject(
                "SELECT id FROM system_config_audit WHERE config_version = 1 AND action = 'INITIALIZE'",
                UUID.class);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            service.restoreConfig(ADMIN, auditId, "admin");
            status.setRollbackOnly();
        });

        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(dbVersion()).isEqualTo(2L);

        SystemConfigView restored = service.restoreConfig(ADMIN, auditId, "admin");
        assertThat(restored.version()).isEqualTo(3L);
        assertThat(service.getActiveConfig().version()).isEqualTo(3L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(2);
        assertThat(dbVersion()).isEqualTo(3L);
    }

    @Test
    @Order(4)
    void a1_t06_staleNotificationCannotOverwriteNewerConfig() {
        service.updateConfig(ADMIN, request(1L, true, 3, 1500L, 20000L), "admin");
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);

        bumpDatabaseVersionTo3();
        service.reloadFromDatabase();
        assertThat(service.getActiveConfig().version()).isEqualTo(3L);

        // 过期广播（version=2）经真实消费者路径到达：版本单调防护直接忽略（幂等），
        // 不回源数据库、不覆盖本地更新的配置
        MapRecord<String, String, String> stale = StreamRecords.newRecord()
                .in(STREAM)
                .withId("999-1")
                .ofMap(Map.of("version", "2"));
        configSync.onMessage(stale);

        assertThat(service.getActiveConfig().version()).isEqualTo(3L);
    }

    @Test
    @Order(5)
    void a1_t07_periodicSyncRecoversLostNotification() {
        service.updateConfig(ADMIN, request(1L, true, 3, 1500L, 20000L), "admin");
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);

        bumpDatabaseVersionTo3();

        service.scheduledSync();

        assertThat(service.getActiveConfig().version()).isEqualTo(3L);
        assertThat(service.getWorkerDelayMs()).isEqualTo(1800L);
    }

    @Test
    @Order(6)
    void a1_t09_concurrentVersionConflictDoesNotApplyLoser() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> updateAfter(start, 1L, 3500L));
            var second = executor.submit(() -> updateAfter(start, 1L, 4500L));
            start.countDown();
            List<String> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).contains("SUCCESS", "VERSION_CONFLICT");
            assertThat(dbVersion()).isEqualTo(2L);
            assertThat(service.getActiveConfig().version()).isEqualTo(2L);
            assertThat(service.getWorkerDelayMs()).isEqualTo(outcomes.indexOf("SUCCESS") == 0 ? 3500L : 4500L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(7)
    void a1_t10_runtimeApplyFailureDoesNotFakeRollbackAndRecoversViaSync() {
        doThrow(new IllegalStateException("模拟应用失败")).when(concurrencyManager).setConcurrency(anyInt());

        service.updateConfig(ADMIN, request(1L, true, 4, 1600L, 21000L), "admin");

        assertThat(dbVersion()).isEqualTo(2L);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(service.getActiveConfig().version()).isEqualTo(1L);

        doNothing().when(concurrencyManager).setConcurrency(anyInt());
        service.scheduledSync();

        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.getWorkerDelayMs()).isEqualTo(1600L);
    }

    @Test
    @Order(8)
    void a1_t11_newInstanceReadsStreamHistoryToCatchUp() {
        // 清流后制造两次更新，让 Stream 只包含本用例的短历史
        stringRedisTemplate.delete(STREAM);
        // 实例掉线期间发生两次更新（广播均已写入 Stream），掉线实例重新上线后
        // 从"0"建组补读短历史即可追平，无需等待周期对账
        service.updateConfig(ADMIN, request(1L, true, 3, 1500L, 20000L), "admin");
        service.updateConfig(ADMIN, request(2L, true, 4, 1700L, 22000L), "admin");
        assertThat(service.getActiveConfig().version()).isEqualTo(3L);

        // "掉线实例"从流开头建组（等价于消费未消费过的全部短历史）
        createGroupFromBeginning("it-replayed");
        assertThat(readFromGroup("it-replayed")).containsExactly("2", "3");

        // 补读到旧版本消息后，真实同步路径的版本单调防护保证只应用最新配置
        service.syncFromBroadcast(2L);
        assertThat(service.getActiveConfig().version()).isEqualTo(3L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);
    }

    @Test
    @Order(99)
    void a1_t05_redisOutageStillCommitsDatabase() {
        REDIS.stop();

        service.updateConfig(ADMIN, request(1L, true, 4, 1700L, 22000L), "admin");

        assertThat(dbVersion()).isEqualTo(2L);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);
    }

    private String updateAfter(CountDownLatch start, long expectedVersion, long workerDelay) {
        try {
            start.await();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.updateConfig(ADMIN, request(expectedVersion, true, 3, workerDelay, 20000L), "admin"));
            return "SUCCESS";
        } catch (Exception e) {
            if (e instanceof com.shitulelv.aicollab.common.exception.BusinessException be) {
                return be.getErrorCode().name();
            }
            return "UNEXPECTED:" + e.getClass().getSimpleName();
        }
    }

    /** 为"新实例"建组：从当前最新位置开始（只收建组后产生的新广播）。 */
    private void createGroup(String group) {
        ensureStreamExists();
        stringRedisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("$"), group);
    }

    /** 从流开头建组（模拟实例掉线重启后补读短历史）。 */
    private void createGroupFromBeginning(String group) {
        ensureStreamExists();
        stringRedisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0"), group);
    }

    /** XGROUP 建组要求流必须已存在（Spring Data 未暴露 MKSTREAM），流为空时写一条占位消息。 */
    private void ensureStreamExists() {
        Long size = stringRedisTemplate.opsForStream().size(STREAM);
        if (size == null || size == 0) {
            stringRedisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(STREAM).ofMap(Map.of("version", "-1")));
        }
    }

    /** 消费者组读取未消费消息（">"语义）。 */
    private List<String> readFromGroup(String group) {
        List<String> versions = new java.util.ArrayList<>();
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(group, "c1"),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        for (MapRecord<String, Object, Object> record : records) {
            versions.add(String.valueOf(record.getValue().get("version")));
        }
        return versions;
    }

    private long streamLength() {
        Long len = stringRedisTemplate.opsForStream().size(STREAM);
        return len != null ? len : 0L;
    }

    private void bumpDatabaseVersionTo3() {
        SystemConfig current = repository.findCurrent().orElseThrow();
        SystemConfig v3 = new SystemConfig(
                current.id(), current.agentEnabled(), current.chatEnabled(), 3, 1800L, 26000L,
                current.systemMaintenanceMode(), current.systemMaintenanceMessage(),
                current.publicRegistrationEnabled(), current.defaultChatModel(), current.defaultAgentModel(),
                current.ragRetrievalThreshold(), current.ragMaxSources(), 3L, ADMIN,
                current.createdAt(), current.updatedAt().plusSeconds(1));
        assertThat(repository.updateWithVersion(v3, 2L)).isTrue();
    }

    private UpdateSystemConfigRequest request(long expectedVersion, boolean agentEnabled,
                                              int concurrency, long workerDelay, long scheduleDelay) {
        return new UpdateSystemConfigRequest(
                agentEnabled, true, concurrency, workerDelay, scheduleDelay,
                false, "", true, "", "", 0.55, 12, expectedVersion);
    }

    private long dbVersion() {
        return jdbc.queryForObject("SELECT version FROM system_configuration WHERE id=?", Long.class, SystemConfig.DEFAULT_ID);
    }

    private int auditCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM system_config_audit WHERE config_version > 1", Integer.class);
        return count == null ? 0 : count;
    }
}
