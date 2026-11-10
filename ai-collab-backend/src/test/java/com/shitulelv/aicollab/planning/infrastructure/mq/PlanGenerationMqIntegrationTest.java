package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.application.TaskPlanModelClient;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划 dispatch 消息化全链路集成测试（M2 §3.1 第 2 行，Testcontainers：Postgres + RocketMQ）。
 *
 * <p>覆盖：事务提交后发 dispatch 消息 → 消费者按消息身份快照调用编排器执行入口
 * （与进程内路径共用）；重复投递按 attemptId 幂等丢弃（注册表 + DB 状态机双保险）。
 * 模型客户端 mock 抛业务异常 → 编排器按既有语义 fail 收口（断言消费确实驱动了执行）。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=true",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket",
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        "document.mq.reconnect-interval=10s",
        "planning.mq.reconnect-interval=10s",
        // 恢复对账 Job 会把 QUEUED/RUNNING 的 stale attempt 标 FAILED，干扰断言，禁用
        "planning.recovery-delay-ms=3600s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class PlanGenerationMqIntegrationTest {

    static final String TOPIC = "plan-generation";
    static final String CONSUMER_GROUP = "plan-generation-consumer";

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Container
    static final GenericContainer<?> NAMESRV = new GenericContainer<>(
            DockerImageName.parse("apache/rocketmq:5.3.3"))
            .withCommand("sh", "mqnamesrv")
            .withEnv("JAVA_OPT_EXT", "-Xms128m -Xmx128m")
            .withNetwork(NETWORK)
            .withNetworkAliases("rocketmq-namesrv")
            .withExposedPorts(9876)
            .waitingFor(Wait.forLogMessage(".*The Name Server boot success.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>(
            DockerImageName.parse("apache/rocketmq:5.3.3"))
            .withCommand("sh", "mqbroker", "-n", "rocketmq-namesrv:9876", "-c", "/conf/broker.conf")
            .withCopyToContainer(Transferable.of("""
                    brokerClusterName = DefaultCluster
                    brokerName = broker-a
                    brokerId = 0
                    brokerIP1 = 127.0.0.1
                    listenPort = 10911
                    brokerRole = ASYNC_MASTER
                    flushDiskType = ASYNC_FLUSH
                    autoCreateTopicEnable = false
                    """), "/conf/broker.conf")
            .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m")
            .withNetwork(NETWORK)
            .withNetworkAliases("rocketmq-broker")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.Ports(
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindPort(10911),
                                    new com.github.dockerjava.api.model.ExposedPort(10911)))))
            .withExposedPorts(10911)
            .waitingFor(Wait.forLogMessage(".*boot success.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("planning.mq.enabled", () -> "true");
        registry.add("planning.mq.namesrv-addr", () -> "127.0.0.1:" + NAMESRV.getMappedPort(9876));
        declareTopic();
    }

    static void declareTopic() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                if (execMqAdmin("updateTopic", "-t", TOPIC)
                        && execMqAdmin("updateTopic", "-t", "%DLQ%" + CONSUMER_GROUP)
                        && execMqAdmin("updateSubGroup", "-g", CONSUMER_GROUP)) {
                    return;
                }
            } catch (Exception exception) {
                if (attempt == 10) {
                    throw new IllegalStateException("测试 topic 声明失败", exception);
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new IllegalStateException("测试 topic 声明失败：重试耗尽");
    }

    static boolean execMqAdmin(String command, String key, String value) throws Exception {
        var result = BROKER.execInContainer("sh", "mqadmin", command,
                "-n", "rocketmq-namesrv:9876", "-c", "DefaultCluster", key, value);
        String output = result.getStdout() + result.getStderr();
        return output.contains("success");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TaskPlanRepository repository;
    @Autowired
    TaskPlanGenerationOrchestrator orchestrator;
    @Autowired
    PlatformTransactionManager transactionManager;
    @MockitoBean
    TaskPlanModelClient model;

    private UUID ownerId;
    private UUID projectId;

    @BeforeAll
    static void relaxAwaitilityDefaults() {
        Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
    }

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbc.update("DELETE FROM ai_task_plan_attempt");
        jdbc.update("DELETE FROM ai_task_plan");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "规划所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "规划 MQ 测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        // 模型抛业务异常：编排器按既有语义 fail 收口（错误码写入计划与 attempt 行），
        // 这是"消费确实驱动了执行入口"的确定性观测点
        when(model.generate(any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PLANNING_MODEL_UNAVAILABLE));
    }

    private TaskPlanRecord createPlan() {
        return repository.create(projectId, ownerId, new CreateTaskPlanRequest(
                "MQ 消息化规划", "验证 dispatch 消息化全链路", null,
                LocalDate.now(), LocalDate.now().plusDays(7), 5, java.util.List.of()));
    }

    /** 事务内 dispatch：与生产路径一致（TaskPlanCommandService 在事务内调用编排器）。 */
    private void dispatchInTransaction(TaskPlanRecord plan, boolean detailOnly) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> orchestrator.dispatch(plan, ownerId, detailOnly));
    }

    private String planStatus(UUID planId) {
        return jdbc.queryForObject("SELECT status FROM ai_task_plan WHERE id=?", String.class, planId);
    }

    @Test
    void dispatchDeliversThroughMqConsumptionToExecutionEntry() {
        TaskPlanRecord plan = createPlan();

        // 事务提交后发 dispatch 消息；消费者以 dispatch 身份快照调用执行入口
        dispatchInTransaction(plan, false);

        // 执行入口（与进程内路径共用）：骨架阶段模型调用 → 业务异常 → 既有 fail 收口
        await().atMost(Duration.ofSeconds(60)).until(() -> "FAILED".equals(planStatus(plan.id())));
        Integer attemptFailures = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id=? AND status='FAILED'",
                Integer.class, plan.id());
        assertThat(attemptFailures).isGreaterThanOrEqualTo(1);
        verify(model, times(1)).generate(any(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateDeliveryIsDroppedByIdempotency() {
        TaskPlanRecord plan = createPlan();
        dispatchInTransaction(plan, false);
        await().atMost(Duration.ofSeconds(60)).until(() -> "FAILED".equals(planStatus(plan.id())));

        // 已收口（FAILED）后重投递同一 dispatch 消息：DB 状态机校验失败直接丢弃，
        // 编排器执行入口不再调用模型（注册表 + 状态机双保险的第二层）
        resend(plan);

        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(model, times(1)).generate(any(), any(), anyString(), any(), any(), any(), any(), any()));
        assertThat(planStatus(plan.id())).isEqualTo("FAILED");
    }

    /** 测试专用 raw producer：模拟 broker 对 dispatch 消息的重投递。 */
    private void resend(TaskPlanRecord plan) {
        try {
            DefaultMQProducer producer = new DefaultMQProducer("plan-mq-duplicate-test");
            producer.setNamesrvAddr("127.0.0.1:" + NAMESRV.getMappedPort(9876));
            producer.start();
            try {
                PlanGenerationMessage payload = new PlanGenerationMessage(
                        plan.projectId(), plan.id(), ownerId, false,
                        plan.generationSeq(), plan.activeAttemptId());
                org.apache.rocketmq.common.message.Message message = new org.apache.rocketmq.common.message.Message(
                        TOPIC, plan.id().toString(),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(payload));
                message.putUserProperty(PlanGenerationMessage.PROP_PLAN_ID, plan.id().toString());
                producer.send(message);
            } finally {
                producer.shutdown();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("测试消息重发失败", failure);
        }
    }
}
