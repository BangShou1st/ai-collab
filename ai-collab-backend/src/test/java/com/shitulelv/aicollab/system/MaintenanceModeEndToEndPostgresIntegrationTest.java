package com.shitulelv.aicollab.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5.2：系统维护停站模式端到端真实 HTTP + SecurityFilterChain + PostgreSQL + Redis 集成测试。
 *
 * <p>覆盖：系统管理员开启/关闭维护模式、匿名状态探针 {@code /api/v1/system/status}、
 * 普通用户登录/刷新/业务接口 503 {@code SYSTEM_MAINTENANCE} + {@code Retry-After: 60}、
 * 以及系统管理员在维护期登录、配置界面与业务接口全放行。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "connector.cleanup.enabled=false",
        "security.jwt.secret=synthetic-test-only-jwt-secret-with-at-least-32-characters",
        "model.config.master-key=synthetic-test-only-master-key",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MaintenanceModeEndToEndPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "synthetic-maintenance-test")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-maintenance-secret")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("storage.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> "synthetic-maintenance-test");
        registry.add("storage.minio.secret-key", () -> "synthetic-maintenance-secret");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder passwordEncoder;
    @MockitoBean
    TaskPlanRecoveryJob recovery;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void maintenanceModeBlocksNonAdminLoginRefreshAndApiWhilePassingAdminAndStatusProbe() throws Exception {
        String adminUser = "maint-admin-" + UUID.randomUUID().toString().substring(0, 6);
        String regularUser = "maint-user-" + UUID.randomUUID().toString().substring(0, 6);
        String rawPass = "Synthetic-Strong-Pass1!";
        insertUser(adminUser, rawPass, true);
        insertUser(regularUser, rawPass, false);

        // 维护开启前：普通用户与管理员均可正常登录并拿到 Access Token + Refresh Cookie
        HttpResponse<String> userLoginBefore = login(regularUser, rawPass);
        assertThat(userLoginBefore.statusCode()).isEqualTo(200);
        String userTokenBefore = json.readTree(userLoginBefore.body()).path("data").path("accessToken").asText();
        String userCookieBefore = extractRefreshCookie(userLoginBefore);
        assertThat(userTokenBefore).isNotBlank();
        assertThat(userCookieBefore).isNotBlank();
        assertThat(get("/api/v1/projects", userTokenBefore).statusCode()).isEqualTo(200);

        HttpResponse<String> adminLoginBefore = login(adminUser, rawPass);
        assertThat(adminLoginBefore.statusCode()).isEqualTo(200);
        String adminToken = json.readTree(adminLoginBefore.body()).path("data").path("accessToken").asText();

        JsonNode initialCfg = json.readTree(get("/api/v1/admin/config", adminToken).body()).path("data");
        long initialVersion = initialCfg.path("version").asLong();

        // 开启维护模式
        HttpResponse<String> enabled = putConfig(adminToken, initialCfg, true, "系统升级维护预计 15 分钟", initialVersion);
        assertThat(enabled.statusCode()).isEqualTo(200);
        long maintenanceVersion = json.readTree(enabled.body()).path("data").path("version").asLong();

        try {
            // 1. 匿名访客探测 /api/v1/system/status 放行并返回公告
            HttpResponse<String> statusOn = get("/api/v1/system/status", null);
            assertThat(statusOn.statusCode()).isEqualTo(200);
            JsonNode statusData = json.readTree(statusOn.body()).path("data");
            assertThat(statusData.path("maintenanceMode").asBoolean()).isTrue();
            assertThat(statusData.path("maintenanceMessage").asText()).contains("系统升级维护");

            // 2. 普通用户新登录被拒（503 SYSTEM_MAINTENANCE）
            HttpResponse<String> userLoginDuring = login(regularUser, rawPass);
            assertThat(userLoginDuring.statusCode()).isEqualTo(503);
            assertThat(json.readTree(userLoginDuring.body()).path("code").asText()).isEqualTo("SYSTEM_MAINTENANCE");

            // 3. 普通用户持历史 refresh Cookie 刷新令牌被拒（503 SYSTEM_MAINTENANCE）
            HttpResponse<String> userRefreshDuring = postWithCookie("/api/v1/auth/refresh", userCookieBefore);
            assertThat(userRefreshDuring.statusCode()).isEqualTo(503);
            assertThat(json.readTree(userRefreshDuring.body()).path("code").asText()).isEqualTo("SYSTEM_MAINTENANCE");

            // 4. 普通用户持维护前签发的 Access Token 调用业务 API 被过滤器拦截（503 + Retry-After: 60）
            HttpResponse<String> userProjectsDuring = get("/api/v1/projects", userTokenBefore);
            assertThat(userProjectsDuring.statusCode()).isEqualTo(503);
            assertThat(userProjectsDuring.headers().firstValue(HttpHeaders.RETRY_AFTER)).contains("60");
            assertThat(json.readTree(userProjectsDuring.body()).path("code").asText()).isEqualTo("SYSTEM_MAINTENANCE");

            // 5. 系统管理员在维护期内仍可登录、读取后台配置并访问业务接口
            HttpResponse<String> adminLoginDuring = login(adminUser, rawPass);
            assertThat(adminLoginDuring.statusCode()).isEqualTo(200);
            String freshAdminToken = json.readTree(adminLoginDuring.body()).path("data").path("accessToken").asText();
            assertThat(get("/api/v1/admin/config", freshAdminToken).statusCode()).isEqualTo(200);
            assertThat(get("/api/v1/projects", freshAdminToken).statusCode()).isEqualTo(200);

            // 6. 关闭维护模式后恢复普通用户与状态探针
            HttpResponse<String> disabled = putConfig(freshAdminToken, initialCfg, false, null, maintenanceVersion);
            assertThat(disabled.statusCode()).isEqualTo(200);

            HttpResponse<String> statusOff = get("/api/v1/system/status", null);
            assertThat(statusOff.statusCode()).isEqualTo(200);
            assertThat(json.readTree(statusOff.body()).path("data").path("maintenanceMode").asBoolean()).isFalse();
            assertThat(get("/api/v1/projects", userTokenBefore).statusCode()).isEqualTo(200);
        } finally {
            // 确保即使断言失败也不遗留维护开关影响同 JVM 后续测试
            try {
                JsonNode current = json.readTree(get("/api/v1/admin/config", adminToken).body()).path("data");
                if (current.path("systemMaintenanceMode").asBoolean(false)) {
                    putConfig(adminToken, current, false, null, current.path("version").asLong());
                }
            } catch (Exception ignored) {
                // ignore cleanup failure
            }
        }
    }

    private void insertUser(String username, String rawPassword, boolean systemAdmin) {
        jdbc.update("""
                        INSERT INTO app_user(
                            id, username, password_hash, display_name, email, status, email_verified, system_admin
                        ) VALUES (?,?,?,?,?,'ACTIVE',true,?)
                        """,
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(rawPassword),
                username,
                username + "@example.invalid",
                systemAdmin);
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("username", username, "password", password));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putConfig(
            String adminToken, JsonNode baseCfg, boolean maintenanceMode, String message, long expectedVersion)
            throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("agentEnabled", baseCfg.path("agentEnabled").asBoolean(true));
        payload.put("chatEnabled", baseCfg.path("chatEnabled").asBoolean(true));
        payload.put("workerConcurrency", baseCfg.path("workerConcurrency").asInt(1));
        payload.put("workerDelayMs", baseCfg.path("workerDelayMs").asLong(3000));
        payload.put("scheduleDelayMs", baseCfg.path("scheduleDelayMs").asLong(15000));
        payload.put("systemMaintenanceMode", maintenanceMode);
        payload.put("systemMaintenanceMessage", message);
        payload.put("publicRegistrationEnabled", baseCfg.path("publicRegistrationEnabled").asBoolean(true));
        payload.put("defaultChatModel", "");
        payload.put("defaultAgentModel", "");
        payload.put("ragRetrievalThreshold", baseCfg.path("ragRetrievalThreshold").asDouble(0.35));
        payload.put("ragMaxSources", baseCfg.path("ragMaxSources").asInt(8));
        payload.put("expectedVersion", expectedVersion);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/v1/admin/config")))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithCookie(String path, String cookiePair) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Origin", "http://localhost:5173")
                .header("Cookie", cookiePair)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractRefreshCookie(HttpResponse<?> response) {
        return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                .map(raw -> raw.split(";", 2)[0])
                .filter(pair -> pair.contains("="))
                .findFirst()
                .orElse("");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
