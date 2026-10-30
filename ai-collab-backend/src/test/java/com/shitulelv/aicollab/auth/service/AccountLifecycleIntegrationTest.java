package com.shitulelv.aicollab.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.mail.MailMessage;
import com.shitulelv.aicollab.mail.MailService;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * P7.1 账号生命周期闭环：忘记密码 / 密码重置 / 邮箱验证的真实 HTTP + PostgreSQL 验证。
 * 邮件通过 Mock MailService 捕获正文链接，令牌明文只存在于邮件正文中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false", "planning.enabled=false",
        "connector.cleanup.enabled=false", "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key", "security.jwt.access-token-minutes=30",
        "app.public-url=https://aicollab.example.edu", "app.mail.enabled=true",
        "app.mail.host=smtp.synthetic.invalid", "app.mail.from=no-reply@aicollab.example.edu"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AccountLifecycleIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "synthetic-account-lifecycle")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-invite-secret").withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("storage.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> "synthetic-account-lifecycle");
        registry.add("storage.minio.secret-key", () -> "synthetic-invite-secret");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired AccessTokenService accessTokens;
    @Autowired PasswordEncoder passwordEncoder;
    @MockitoBean TaskPlanRecoveryJob recovery;
    @MockitoBean MailService mailService;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final List<MailMessage> outbox = new ArrayList<>();

    private void captureMails() {
        doAnswer(invocation -> {
            outbox.add(invocation.getArgument(0));
            return null;
        }).when(mailService).send(any());
    }

    private String latestResetToken() {
        verify(mailService, atLeast(1)).send(any());
        String body = outbox.get(outbox.size() - 1).textBody();
        var matcher = Pattern.compile("https://aicollab\\.example\\.edu/reset-password\\?token=([A-Za-z0-9_-]{43})")
                .matcher(body);
        assertThat(matcher.find()).as("密码重置链接应出现在邮件正文中").isTrue();
        return matcher.group(1);
    }

    private String latestVerificationToken() {
        verify(mailService, atLeast(1)).send(any());
        String body = outbox.get(outbox.size() - 1).textBody();
        var matcher = Pattern.compile("https://aicollab\\.example\\.edu/verify-email\\?token=([A-Za-z0-9_-]{43})")
                .matcher(body);
        assertThat(matcher.find()).as("邮箱验证链接应出现在邮件正文中").isTrue();
        return matcher.group(1);
    }

    @Test
    void forgot_password_reset_flow_is_anti_enumerable_one_shot_and_revokes_sessions() throws Exception {
        captureMails();
        UUID userId = userWithBcryptPassword("reset-user", "reset@example.invalid");
        jdbc.update("INSERT INTO refresh_token(id,user_id,token_hash,session_id,session_expires_at,expires_at) "
                        + "VALUES (?,?,?,gen_random_uuid(),now()+interval '1 hour',now()+interval '1 hour')",
                UUID.randomUUID(), userId, "synthetic-refresh-hash");

        // 未知邮箱与已知邮箱响应一致，且未知邮箱不产生邮件
        var unknown = post("/api/v1/auth/forgot-password", null, "{\"email\":\"ghost@example.invalid\"}");
        assertThat(unknown.statusCode()).isEqualTo(200);
        int mailsBefore = outbox.size();
        var known = post("/api/v1/auth/forgot-password", null, "{\"email\":\"Reset@Example.invalid\"}");
        assertThat(known.statusCode()).isEqualTo(200);
        assertThat(outbox.size()).isEqualTo(mailsBefore + 1);

        String rawToken = latestResetToken();
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM account_action_token WHERE user_id=? AND token_type='PASSWORD_RESET'",
                String.class, userId);
        assertThat(storedHash).isNotEqualTo(rawToken).hasSize(64);

        var reset = post("/api/v1/auth/reset-password", null,
                "{\"token\":\"" + rawToken + "\",\"newPassword\":\"Synthetic-Only-Strong1!\"}");
        assertThat(reset.statusCode()).isEqualTo(200);
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, userId);
        assertThat(passwordEncoder.matches("Synthetic-Only-Strong1!", passwordHash)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id=? AND revoked_at IS NULL", Integer.class, userId))
                .isZero();

        // 同一令牌重放被拒绝（410 + ACCOUNT_TOKEN_INVALID）
        var replay = post("/api/v1/auth/reset-password", null,
                "{\"token\":\"" + rawToken + "\",\"newPassword\":\"Another-Strong-Pass1!\"}");
        assertThat(replay.statusCode()).isEqualTo(410);
        assertThat(json.readTree(replay.body()).path("code").asText()).isEqualTo("ACCOUNT_TOKEN_INVALID");
    }

    @Test
    void expired_reset_token_is_rejected() throws Exception {
        captureMails();
        UUID userId = userWithBcryptPassword("reset-expired", "expired@example.invalid");
        post("/api/v1/auth/forgot-password", null, "{\"email\":\"expired@example.invalid\"}");
        String rawToken = latestResetToken();
        jdbc.update("UPDATE account_action_token SET expires_at=now()-interval '1 minute' "
                + "WHERE user_id=? AND token_type='PASSWORD_RESET'", userId);

        var reset = post("/api/v1/auth/reset-password", null,
                "{\"token\":\"" + rawToken + "\",\"newPassword\":\"Synthetic-Only-Strong1!\"}");
        assertThat(reset.statusCode()).isEqualTo(410);
    }

    @Test
    void email_verification_flow_marks_verified_exactly_once() throws Exception {
        captureMails();
        UUID userId = userWithBcryptPassword("verify-user", "verify@example.invalid");
        String accessToken = accessTokens.createAccessToken(users.findById(userId).orElseThrow()).value();

        var requested = post("/api/v1/auth/verify-email/request", accessToken, "");
        assertThat(requested.statusCode()).isEqualTo(200);
        String rawToken = latestVerificationToken();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM account_action_token "
                + "WHERE user_id=? AND token_type='EMAIL_VERIFICATION'", String.class, userId))
                .isNotEqualTo(rawToken);

        var verified = post("/api/v1/auth/verify-email", null, "{\"token\":\"" + rawToken + "\"}");
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM app_user WHERE id=?", Boolean.class, userId))
                .isTrue();

        var replay = post("/api/v1/auth/verify-email", null, "{\"token\":\"" + rawToken + "\"}");
        assertThat(replay.statusCode()).isEqualTo(410);
    }

    private UUID userWithBcryptPassword(String username, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name,email,status) VALUES (?,?,?,'test',?,'ACTIVE')",
                id, username, passwordEncoder.encode("Synthetic-Original-Pass1!"), email);
        return id;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
