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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
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
    private static final String CHANNEL = "system:config:change";

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
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AgentDynamicScheduleRegistrar dynamicScheduleRegistrar;
    @MockitoBean com.shitulelv.aicollab.agent.application.AgentWorkerConcurrencyManager concurrencyManager;

    private final CopyOnWriteArrayList<String> receivedMessages = new CopyOnWriteArrayList<>();
    private RedisConnection subscriptionConnection;

    @BeforeEach
    void resetConfiguration() {
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
        receivedMessages.clear();
    }

    @AfterEach
    void closeSubscription() {
        if (subscriptionConnection != null) {
            try {
                subscriptionConnection.close();
            } catch (Exception ignored) {
                // 订阅连接关闭失败不影响断言
            }
            subscriptionConnection = null;
        }
    }

    private void subscribe() {
        CountDownLatch subscribed = new CountDownLatch(1);
        subscriptionConnection = stringRedisTemplate.getConnectionFactory().getConnection();
        subscriptionConnection.subscribe(
                (message, pattern) -> {
                    receivedMessages.add(new String(message.getBody(), StandardCharsets.UTF_8));
                    subscribed.countDown();
                },
                CHANNEL.getBytes(StandardCharsets.UTF_8));
        awaitLatch(subscribed, 2_000);
    }

    @Test
    @Order(1)
    void a1_t01_t04_appliesRuntimeAndBroadcastsOnlyAfterCommit() {
        subscribe();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AtomicReference<Long> runtimeVersionInsideTx = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            service.updateConfig(ADMIN, request(1L, true, 4, 1500L, 20000L), "admin");
            runtimeVersionInsideTx.set(service.getActiveConfig().version());
        });

        assertThat(runtimeVersionInsideTx.get()).isEqualTo(1L);

        awaitLatch(new CountDownLatch(receivedMessages.isEmpty() ? 1 : 0), 2_000);
        assertThat(receivedMessages).containsExactly("2");
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);
        assertThat(service.getWorkerDelayMs()).isEqualTo(1500L);
        assertThat(dbVersion()).isEqualTo(2L);
    }

    @Test
    @Order(2)
    void a1_t02_t03_rollbackKeepsRuntimeAndDbAndSilencesBroadcast() {
        subscribe();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            service.updateConfig(ADMIN, request(1L, true, 3, 2500L, 25000L), "admin");
            status.setRollbackOnly();
        });

        assertThat(service.getActiveConfig().version()).isEqualTo(1L);
        assertThat(service.getWorkerConcurrency()).isEqualTo(2);
        assertThat(dbVersion()).isEqualTo(1L);
        assertThat(auditCount()).isZero();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(receivedMessages).isEmpty();
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

        configSync.onMessage(new StubMessage(CHANNEL, "2"), new byte[0]);

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

    private static void awaitLatch(CountDownLatch latch, long timeoutMillis) {
        try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record StubMessage(String channel, String body)
            implements org.springframework.data.redis.connection.Message {
        @Override
        public byte[] getBody() {
            return body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] getChannel() {
            return channel.getBytes(StandardCharsets.UTF_8);
        }
    }
}
