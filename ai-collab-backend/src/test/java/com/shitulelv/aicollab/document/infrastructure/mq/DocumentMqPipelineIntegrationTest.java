package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentRegistrationService;
import com.shitulelv.aicollab.document.application.service.DocumentProcessingService;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档链路 MQ 迁移端到端集成测试（M1，Testcontainers：Postgres + RocketMQ）。
 *
 * <p>覆盖：上传 → 事务消息 → 消费 → claim（PARSING）→ READY 全链路；重复投递幂等
 * （同 documentId+stage 二次投递只处理一次）；消费异常 → RECONSUME_LATER 重试路径；
 * 注册业务失败的半消息回滚。</p>
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
        // 恢复 Job 干扰断言，MQ 链路由真实 broker 消息驱动
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        "document.mq.reconnect-interval=10s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentMqPipelineIntegrationTest {

    static final String TOPIC = "document-processing";
    static final String CONSUMER_GROUP = "document-processing-consumer";

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
            // 客户端按 broker 注册地址（127.0.0.1:10911）直连，映射必须固定
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
        registry.add("document.mq.enabled", () -> "true");
        registry.add("document.mq.namesrv-addr", () -> "127.0.0.1:" + NAMESRV.getMappedPort(9876));
        // topic 显式声明（与 prod rocketmq-topic-init 同构），必须在应用上下文（消费者）启动前完成
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

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentRegistrationService registration;
    @MockitoSpyBean
    DocumentProcessingService processing;
    @MockitoBean
    DocumentStorageGateway storage;
    @MockitoBean
    ProjectEmbeddingGateway embeddingGateway;
    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID ownerId;
    private UUID projectId;

    @BeforeAll
    static void relaxAwaitilityDefaults() {
        Awaitility.setDefaultPollInterval(Duration.ofMillis(500));
    }

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "文档所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "MQ 链路测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        // 嵌入配置被向量表外键引用（不可 TRUNCATE），用 DELETE 清理后再插入单活动行
        jdbc.update("DELETE FROM system_embedding_config");
        insertActiveEmbeddingConfig();
        when(storage.open(anyString()))
                .thenReturn(new ByteArrayInputStream("AI Collab 文档处理链路测试内容".getBytes(StandardCharsets.UTF_8)));
        when(embeddingGateway.embed(any(UUID.class), anyList(), any())).thenAnswer(invocation -> {
            List<String> input = invocation.getArgument(1);
            List<List<Double>> vectors = input.stream()
                    .map(ignored -> List.of(0.1, 0.2, 0.3, 0.4))
                    .toList();
            return new EmbeddingBatch(EMBEDDING_PROVIDER, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, vectors);
        });
    }

    /** 索引写入校验活动嵌入配置指纹，须与 mock 网关输出一致（generation 置空对齐）。 */
    private static final String EMBEDDING_PROVIDER = "OPENAI_COMPATIBLE";
    private static final String EMBEDDING_MODEL = "mock-model";
    private static final int EMBEDDING_DIMENSIONS = 4;

    private void insertActiveEmbeddingConfig() {
        jdbc.update("""
                INSERT INTO system_embedding_config
                    (id, provider, base_url, model_name, dimensions, batch_size, fingerprint, enabled)
                VALUES (?, ?, 'http://127.0.0.1:1', ?, ?, 16, ?, TRUE)
                """,
                UUID.randomUUID(), EMBEDDING_PROVIDER, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints
                        .fingerprint(EMBEDDING_PROVIDER, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS));
    }

    private DocumentEntity document() {
        UUID documentId = UUID.randomUUID();
        DocumentEntity entity = new DocumentEntity();
        entity.setId(documentId);
        entity.setProjectId(projectId);
        entity.setDisplayName("链路测试文档");
        entity.setOriginalFilename("pipeline.txt");
        entity.setMimeType("text/plain");
        entity.setSizeBytes(32L);
        entity.setObjectKey("projects/" + projectId + "/documents/" + documentId + "/source.txt");
        entity.setStatus(DocumentStatus.UPLOADED);
        entity.setChunkCount(0);
        entity.setUploadedBy(ownerId);
        return entity;
    }

    private DocumentStatus statusOf(UUID documentId) {
        return DocumentStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM project_document WHERE project_id = ? AND id = ?",
                String.class, projectId, documentId));
    }

    @Test
    void uploadDeliversThroughMqConsumptionToReady() {
        DocumentEntity entity = document();

        registration.registerUploadedDocument(entity);

        // 全链路：事务消息 → 消费 → claim（UPLOADED→PARSING CAS）→ 解析 → 索引 → READY
        await().atMost(Duration.ofSeconds(60)).until(
                () -> statusOf(entity.getId()) == DocumentStatus.READY);
        verify(processing, times(1)).process(projectId, entity.getId());

        // 消费幂等占位已落库（复用 idempotency_record，key = documentId + stage）
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE endpoint = ? AND idempotency_key = ?",
                Integer.class, "mq:document-processing:parse", entity.getId());
        assertThat(idempotentRows).isEqualTo(1);
        // 处理副作用：分块与向量已写入
        Integer chunkRows = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?",
                Integer.class, entity.getId());
        assertThat(chunkRows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void duplicateDeliveryIsProcessedExactlyOnce() throws Exception {
        DocumentEntity entity = document();
        registration.registerUploadedDocument(entity);
        await().atMost(Duration.ofSeconds(60)).until(
                () -> statusOf(entity.getId()) == DocumentStatus.READY);

        // 同 documentId+stage 二次真实投递（raw producer 模拟 broker 重投递）
        resend(entity.getId());

        // 给重投递留出消费时间后，处理副作用仍恰好一次（状态前置校验 + 幂等占位拦截）
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                verify(processing, times(1)).process(projectId, entity.getId()));
        assertThat(statusOf(entity.getId())).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void consumptionFailureRetriesUntilSuccess() {
        DocumentEntity entity = document();
        // 第一次消费抛系统级异常 → RECONSUME_LATER（延迟等级指数退避），重投递后成功
        org.mockito.Mockito.doThrow(new IllegalStateException("数据库瞬时不可用"))
                .doCallRealMethod()
                .when(processing).process(any(UUID.class), any(UUID.class));

        registration.registerUploadedDocument(entity);

        await().atMost(Duration.ofSeconds(120)).until(
                () -> statusOf(entity.getId()) == DocumentStatus.READY);
        verify(processing, times(2)).process(projectId, entity.getId());
    }

    @Test
    void registrationBusinessFailureRollsBackWithoutDocumentRow() {
        // 项目状态改为 COMPLETED：注册事务失败（PROJECT_READ_ONLY），半消息回滚
        jdbc.update("UPDATE project SET status = 'COMPLETED' WHERE id = ?", projectId);
        DocumentEntity entity = document();

        assertThatThrownBy(() -> registration.registerUploadedDocument(entity))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id = ?", Integer.class, entity.getId());
        assertThat(rows).isZero();
    }

    /** 测试专用 raw producer：直接向业务 topic 重发同一 documentId+stage 的消息。 */
    private void resend(UUID documentId) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("duplicate-delivery-test");
        producer.setNamesrvAddr("127.0.0.1:" + NAMESRV.getMappedPort(9876));
        try {
            producer.start();
            org.apache.rocketmq.common.message.Message message = new org.apache.rocketmq.common.message.Message(
                    TOPIC, documentId.toString(), "{\"resend\":true}".getBytes(StandardCharsets.UTF_8));
            message.putUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID, projectId.toString());
            message.putUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID, documentId.toString());
            message.putUserProperty(DocumentProcessingMessage.PROP_STAGE, DocumentProcessingMessage.STAGE_PARSE);
            producer.send(message);
        } finally {
            producer.shutdown();
        }
    }
}
