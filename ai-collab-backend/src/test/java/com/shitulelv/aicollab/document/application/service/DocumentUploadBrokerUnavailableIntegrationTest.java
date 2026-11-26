package com.shitulelv.aicollab.document.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B1：broker 不可用时上传接口返回 503，且不进入注册、解析或进程内派发。
 *
 * <p>MQ 开启（与生产默认一致），使用真实 RocketMQ 事务生产者与消费者，NameServer 指向
 * 不可达地址 —— 事务半消息发送真实失败（无路由），不 mock 任何消息语义。对象存储与恢复
 * Job 只是旁路依赖，用替身隔离；PostgreSQL 为真实容器。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "connector.cleanup.enabled=false",
        "security.jwt.secret=synthetic-test-only-jwt-secret-for-broker-outage-0123456789",
        "model.config.master-key=synthetic-test-only-master-key",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket",
        // 恢复 Job 不参与本测试
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        // MQ 开启，NameServer 不可达：真实客户端拿不到 topic 路由，事务半消息发送失败
        "document.mq.enabled=true",
        "document.mq.namesrv-addr=127.0.0.1:1",
        "document.mq.reconnect-interval=3600s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentUploadBrokerUnavailableIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserService users;
    @Autowired
    AccessTokenService accessTokens;
    @Autowired
    DocumentPipelineMessenger messenger;
    @MockitoSpyBean
    DocumentRegistrationTransaction registrationTransaction;
    @MockitoSpyBean
    DocumentProcessingService processing;
    @MockitoSpyBean
    DocumentTaskDispatcher dispatcher;
    @MockitoBean
    DocumentStorageGateway storage;
    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "outage-" + ownerId.toString().substring(0, 8), "synthetic-hash", "文档所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "broker 不可用测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
    }

    @Test
    void uploadReturns503AndNeverRegistersOrParsesWhenBrokerIsUnavailable() throws Exception {
        // 装配的是真实 RocketMQ 门面，而不是任何替身
        assertThat(messenger).isInstanceOf(
                com.shitulelv.aicollab.document.infrastructure.mq.RocketMqDocumentPipelineMessenger.class);

        HttpResponse<String> response = upload("broker-outage.txt", "broker 不可用时的上传内容");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("DOCUMENT_PIPELINE_UNAVAILABLE");

        // 注册本地事务没有执行，没有文档行、没有审计
        verify(registrationTransaction, never()).register(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE project_id = ?", Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id = ? AND action = 'DOCUMENT_UPLOADED'",
                Integer.class, projectId)).isZero();
        // 没有进入解析，也没有进程内派发
        verify(processing, never()).process(any(), any());
        verify(dispatcher, never()).dispatch(any(), any());
        verify(dispatcher, never()).runOnce(any(), any());
        // 已写入的对象被补偿删除
        verify(storage).put(anyString(), any(), anyLong(), anyString());
        verify(storage).delete(anyString());
    }

    private HttpResponse<String> upload(String filename, String content) throws Exception {
        String boundary = "----p0-outage-" + UUID.randomUUID();
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(content.getBytes(StandardCharsets.UTF_8));
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String token = accessTokens.createAccessToken(users.findById(ownerId).orElseThrow()).value();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/projects/" + projectId + "/documents"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
