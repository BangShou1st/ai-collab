package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回落路径集成测试（{@code MQ_ENABLED=false}）：文档链路完全走进程内线程池派发，
 * 不依赖任何 MQ 组件（上下文中不存在 RocketMQ 客户端 Bean）。
 * 这是 MQ 总闸"必须真实可回落"的验收面：上传 → AFTER_COMMIT 事件 → 线程池 → READY。
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
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentMqDisabledFallbackIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentRegistrationService registration;
    @Autowired
    DocumentPipelineMessenger messenger;
    @MockitoBean
    DocumentStorageGateway storage;
    @MockitoBean
    ProjectEmbeddingGateway embeddingGateway;
    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        // 嵌入配置带"单活动行"唯一索引且被向量表外键引用（不可 TRUNCATE），用 DELETE 清理
        jdbc.update("DELETE FROM system_embedding_config");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "文档所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "回落模式测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        insertActiveEmbeddingConfig();
        when(storage.open(anyString()))
                .thenReturn(new ByteArrayInputStream("回落模式文档内容".getBytes(StandardCharsets.UTF_8)));
        when(embeddingGateway.embed(any(UUID.class), anyList(), any())).thenAnswer(invocation -> {
            List<String> input = invocation.getArgument(1);
            return new EmbeddingBatch(EMBEDDING_PROVIDER, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, input.stream()
                    .map(ignored -> List.of(0.1, 0.2, 0.3, 0.4)).toList());
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

    @Test
    void messengerPortIsDisabledAndUploadFallsBackToInProcessDispatch() {
        // 总闸关闭：门面为回落实现，MQ 客户端 Bean（生产者/消费者）不存在
        assertThat(messenger.enabled()).isFalse();

        UUID documentId = UUID.randomUUID();
        DocumentEntity entity = new DocumentEntity();
        entity.setId(documentId);
        entity.setProjectId(projectId);
        entity.setDisplayName("回落文档");
        entity.setOriginalFilename("fallback.txt");
        entity.setMimeType("text/plain");
        entity.setSizeBytes(16L);
        entity.setObjectKey("projects/" + projectId + "/documents/" + documentId + "/source.txt");
        entity.setStatus(DocumentStatus.UPLOADED);
        entity.setChunkCount(0);
        entity.setUploadedBy(ownerId);

        registration.registerUploadedDocument(entity);

        // 回落路径：注册事务提交 → AFTER_COMMIT 事件 → documentTaskExecutor → READY
        await().atMost(java.time.Duration.ofSeconds(30)).until(() ->
                DocumentStatus.valueOf(jdbc.queryForObject(
                        "SELECT status FROM project_document WHERE id = ?",
                        String.class, documentId)) == DocumentStatus.READY);
        // 回落模式不产生 MQ 消费幂等占位
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?",
                Integer.class, documentId);
        assertThat(idempotentRows).isZero();
    }

    @Test
    void disabledMessengerImplementationIsWiredWhenSwitchOff() {
        // 装配断言：总闸关闭时门面为回落实现（MQ 客户端 Bean 不创建）
        assertThat(messenger).isInstanceOf(
                com.shitulelv.aicollab.document.infrastructure.mq.DisabledDocumentPipelineMessenger.class);
    }
}
