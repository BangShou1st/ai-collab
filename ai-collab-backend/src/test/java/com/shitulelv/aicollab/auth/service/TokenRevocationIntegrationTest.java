package com.shitulelv.aicollab.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-2 真库 + 真实 HTTP 闭环：停用、改密、logoutAll 后旧 Access/Refresh 立即失效，
 * 重新启用不会复活旧 Token；无 tv 的旧格式 Access Token 只在 token_version=0 时兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false", "planning.enabled=false",
        "agent.enabled=false", "connector.cleanup.enabled=false",
        "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key", "security.jwt.access-token-minutes=15",
        "app.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TokenRevocationIntegrationTest {

    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "synthetic-token-revoke")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-token-secret").withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("storage.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> "synthetic-token-revoke");
        registry.add("storage.minio.secret-key", () -> "synthetic-token-secret");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired AccessTokenService accessTokens;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired UserSessionStateService sessionStates;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean TaskPlanRecoveryJob recovery;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void disable_revokes_access_and_refresh_and_reenable_does_not_resurrect_old_tokens() throws Exception {
        UUID adminId = user("token-admin", "Admin-Password1!", true);
        UUID userId = user("token-disabled", "Original-Password1!", false);
        String oldAccess = access(userId);
        String oldRefresh = refresh(userId);

        HttpResponse<String> disabled = post(
                "/api/v1/admin/users/" + userId + "/disable", access(adminId), null, "");
        assertThat(disabled.statusCode()).isEqualTo(204);

        assertThat(me(oldAccess).statusCode()).isEqualTo(401);
        assertThat(json.readTree(me(oldAccess).body()).path("code").asText())
                .isEqualTo("AUTH_SESSION_REVOKED");
        assertThat(refresh(oldRefresh).statusCode()).isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT token_version FROM app_user WHERE id=?", Integer.class, userId))
                .isEqualTo(1);
        HttpResponse<String> enabled = post(
                "/api/v1/admin/users/" + userId + "/enable", access(adminId), null, "");
        assertThat(enabled.statusCode()).isEqualTo(204);
        assertThat(me(oldAccess).statusCode()).isEqualTo(401);
        assertThat(refresh(oldRefresh).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT token_version FROM app_user WHERE id=?", Integer.class, userId))
                .isEqualTo(2);

        assertThat(me(access(userId)).statusCode()).isEqualTo(200);
    }

    @Test
    void password_change_revokes_all_sessions_and_other_device_token() throws Exception {
        UUID userId = user("token-password", "Original-Password1!", false);
        String deviceA = access(userId);
        String deviceB = access(userId);
        String refresh = refresh(userId);

        HttpResponse<String> changed = post("/api/v1/auth/change-password", deviceA, null,
                "{\"currentPassword\":\"Original-Password1!\",\"newPassword\":\"Changed-Password2!\"}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(me(deviceA).statusCode()).isEqualTo(401);
        assertThat(me(deviceB).statusCode()).isEqualTo(401);
        assertThat(refresh(refresh).statusCode()).isEqualTo(401);
        assertThat(me(access(userId)).statusCode()).isEqualTo(200);
    }

    @Test
    void logout_all_revokes_every_refresh_and_access_token() throws Exception {
        UUID userId = user("token-logout-all", "Original-Password1!", false);
        String access = access(userId);
        String refresh = refresh(userId);

        HttpResponse<String> loggedOut = post("/api/v1/auth/logout-all", access, null, "");
        assertThat(loggedOut.statusCode()).isEqualTo(200);
        assertThat(me(access).statusCode()).isEqualTo(401);
        assertThat(refresh(refresh).statusCode()).isEqualTo(401);
        assertThat(me(access(userId)).statusCode()).isEqualTo(200);
    }

    @Test
    void legacy_access_token_without_tv_works_only_at_version_zero() throws Exception {
        UUID userId = user("token-legacy", "Original-Password1!", false);
        String legacy = legacyAccessWithoutTv(userId);

        assertThat(me(legacy).statusCode()).isEqualTo(200);

        users.bumpTokenVersion(userId);
        sessionStates.evict(userId);

        assertThat(me(legacy).statusCode()).isEqualTo(401);
    }

    private UUID user(String username, String rawPassword, boolean systemAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO app_user(
                            id, username, password_hash, display_name, email, status, system_admin
                        ) VALUES (?,?,?,?,?,'ACTIVE',?)
                        """,
                id, username, passwordEncoder.encode(rawPassword), username,
                username + "@example.invalid", systemAdmin);
        return id;
    }

    private String access(UUID userId) {
        return accessTokens.createAccessToken(users.findById(userId).orElseThrow(
                () -> new IllegalStateException("missing test user"))).value();
    }

    private String refresh(UUID userId) {
        return refreshTokens.createSession(users.findById(userId).orElseThrow(
                () -> new IllegalStateException("missing test user"))).value();
    }

    private String legacyAccessWithoutTv(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("username", users.findById(userId).orElseThrow().getUsername())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private HttpResponse<String> me(String accessToken) throws Exception {
        return get("/api/v1/auth/me", accessToken);
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return post("/api/v1/auth/refresh", null,
                "ai_collab_refresh_token=" + refreshToken, "");
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "http://localhost:5173")
                .GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String accessToken, String cookie, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
