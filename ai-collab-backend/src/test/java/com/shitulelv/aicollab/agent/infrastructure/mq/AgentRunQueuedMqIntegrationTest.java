package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.application.view.AgentSessionView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Agent run 入队通知双轨全链路集成测试（M2 §3.1 第 3 行，Testcontainers：Postgres + RocketMQ）。
 *
 * <p>覆盖：run 创建 afterCommit 发消息 → MQ 消费者触发 claim 尝试（workerId 以
 * {@code mq-wake-} 前缀落租约列）；重复/乱序投递无副作用（claim 是幂等竞争）。
 * 调度 tick 兜底在本测试中被压制（system_configuration.worker_delay_ms=60000），
 * MQ 是唯一的派发触发器 —— 断言才不会与 tick 竞争产生假阳性。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket",
        // 恢复/规划调度干扰断言，MQ 链路由真实 broker 消息驱动
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        "document.mq.reconnect-interval=10s",
        "agent.mq.reconnect-interval=10s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AgentRunQueuedMqIntegrationTest {

    static final String TOPIC = "agent-run-queued";
    static final String CONSUMER_GROUP = "agent-run-queued-consumer";

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
            // 与 prod broker.conf 同构（autoCreateTopicEnable=false）；brokerIP1=127.0.0.1：
            // 客户端（测试 JVM）经 NameServer 获取该地址，走 localhost 固定映射端口直连
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
        registry.add("agent.mq.enabled", () -> "true");
        registry.add("agent.mq.namesrv-addr", () -> "127.0.0.1:" + NAMESRV.getMappedPort(9876));
        // topic 显式声明（与 prod rocketmq-topic-init 同构），必须在应用上下文（消费者）启动前完成
        declareTopic();
        seedSystemConfiguration();
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
            // mqadmin 失败路径也返回退出码 0（已实测），必须以输出判定成败；
            // broker 尚未在 NameServer 完成注册时会失败，等待后重试
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new IllegalStateException("测试 topic 声明失败：重试耗尽");
    }

    /** 执行 mqadmin 并以输出判定成败（退出码不可靠）。 */
    static boolean execMqAdmin(String command, String key, String value) throws Exception {
        var result = BROKER.execInContainer("sh", "mqadmin", command,
                "-n", "rocketmq-namesrv:9876", "-c", "DefaultCluster", key, value);
        String output = result.getStdout() + result.getStderr();
        return output.contains("success");
    }

    /**
     * 压制调度 tick 兜底（worker_delay_ms 拉到 60s 上限）：本测试只验证 MQ 触发路径，
     * tick 若保持 1s 会与 MQ 竞争领取，断言"MQ 是触发器"就失去意义。
     */
    static void seedSystemConfiguration() {
        try {
            var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            // 上下文启动前手动迁移一次，保证 system_configuration 行已存在
            org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration").load().migrate();
            new JdbcTemplate(dataSource).update(
                    "UPDATE system_configuration SET agent_enabled=TRUE, worker_delay_ms=60000");
        } catch (Exception failure) {
            throw new IllegalStateException("测试 system_configuration 预置失败", failure);
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentRepository repository;
    @MockitoSpyBean
    AgentWorker worker;
    @MockitoBean
    com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID ownerId;
    private UUID projectId;
    private AgentSessionView session;

    @BeforeAll
    static void relaxAwaitilityDefaults() {
        Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
    }

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbc.update("DELETE FROM agent_planning_operation_event");
        jdbc.update("DELETE FROM agent_planning_operation");
        jdbc.update("DELETE FROM agent_usage_settlement");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
        org.mockito.Mockito.clearInvocations(worker);
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "Agent 所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "Agent MQ 双轨测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        session = repository.createSession(projectId, ownerId, "MQ 双轨测试会话");
        // worker 只做占位（不执行模型轮次）：本测试验证的是 claim 竞争与触发链路
        doAnswer(invocation -> null).when(worker).process(any());
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?", String.class, runId);
    }

    private String runLeaseOwner(UUID runId) {
        return jdbc.queryForObject("SELECT lease_owner FROM agent_run WHERE id = ?", String.class, runId);
    }

    @Test
    void runCreationNotifiesThroughMqAndGetsClaimed() {
        // run 创建（afterCommit 落点 RUN_CREATED）→ MQ 消费者触发 claim → RUNNING
        var run = repository.createRun(projectId, session.id(), ownerId, "MQ 触发的运行", false, null, null);

        await().atMost(Duration.ofSeconds(30)).until(() -> "RUNNING".equals(runStatus(run.id())));
        // 领取者是 MQ 消费者（workerId 前缀 mq-wake-），不是调度 tick（embedded-）
        assertThat(runLeaseOwner(run.id())).startsWith("mq-wake-");
        verify(worker, times(1)).process(any());
    }

    @Test
    void duplicateAndOutOfOrderDeliveryHasNoSideEffects() {
        var run = repository.createRun(projectId, session.id(), ownerId, "重复投递运行", false, null, null);
        await().atMost(Duration.ofSeconds(30)).until(() -> "RUNNING".equals(runStatus(run.id())));

        // 直接插入一个 QUEUED 运行（不走发送落点），再用 raw producer 模拟 broker 的
        // 重复（3 次）与乱序（source 与发送顺序交错）投递
        UUID queuedRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                SELECT ?,s.id,s.project_id,?,'直接插入的排队运行','QUEUED'
                FROM agent_session s WHERE s.id=?
                """, queuedRunId, ownerId, session.id());

        resend(queuedRunId, "TOOL_RESULT_REQUEUED");
        resend(queuedRunId, "RUN_CREATED");
        resend(queuedRunId, "TOOL_RESULT_REQUEUED");

        // 恰好一次 claim（SKIP LOCKED 幂等竞争），重复消息无副作用
        await().atMost(Duration.ofSeconds(30)).until(() -> "RUNNING".equals(runStatus(queuedRunId)));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(
                () -> "RUNNING".equals(runStatus(queuedRunId)));
        assertThat(runLeaseOwner(queuedRunId)).startsWith("mq-wake-");
        Integer claimVersions = jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE status='RUNNING' AND claim_version=1",
                Integer.class);
        assertThat(claimVersions).isEqualTo(2);
        // 两个运行各被处理一次（创建路径 + 重复消息路径）
        verify(worker, times(2)).process(any());
    }

    /** 测试专用 raw producer：直接向 agent-run-queued topic 发送入队通知消息。 */
    private void resend(UUID runId, String source) {
        try {
            DefaultMQProducer producer = new DefaultMQProducer("agent-mq-duplicate-test");
            producer.setNamesrvAddr("127.0.0.1:" + NAMESRV.getMappedPort(9876));
            producer.start();
            try {
                AgentRunQueuedMessage payload = AgentRunQueuedMessage.parse(runId, source);
                org.apache.rocketmq.common.message.Message message = new org.apache.rocketmq.common.message.Message(
                        TOPIC, runId.toString(),
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsBytes(payload));
                message.putUserProperty(AgentRunQueuedMessage.PROP_RUN_ID, runId.toString());
                message.putUserProperty(AgentRunQueuedMessage.PROP_SOURCE, source);
                producer.send(message);
            } finally {
                producer.shutdown();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("测试消息重发失败", failure);
        }
    }
}
