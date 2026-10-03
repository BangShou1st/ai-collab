package com.shitulelv.aicollab.acceptance;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real providers and persistent isolated storage; no model or outbound-policy mocks. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=18080", "spring.flyway.enabled=true", "agent.enabled=true",
    "chat.enabled=false", "planning.enabled=false", "embedding.enabled=false",
    "security.refresh-token.cookie-secure=false", "security.cors.allowed-origins=http://localhost:15173",
    "security.jwt.access-token-minutes=60"
})
@EnabledIfEnvironmentVariable(named="AI_REAL_ACCEPTANCE",matches="true")
class RealAcceptanceHostTest {
    static final Path STOP = Path.of("target/real-acceptance.stop");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        // Enforces the explicit offline-copy flag and fixed localhost:55432 URL.
        var dataSource = AcceptanceDatabaseSupport.dataSource();
        var properties = AcceptanceDatabaseSupport.localProperties();
        registry.add("spring.datasource.url", dataSource::getUrl);
        registry.add("spring.datasource.username", dataSource::getUsername);
        registry.add("spring.datasource.password", dataSource::getPassword);
        registry.add("security.jwt.secret", () -> properties.getProperty("JWT_SECRET"));
        registry.add("model.config.master-key", () -> properties.getProperty("MODEL_CONFIG_MASTER_KEY"));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 16379);
        registry.add("storage.minio.endpoint", () -> "http://127.0.0.1:18090");
        registry.add("storage.minio.access-key", () -> "acceptance");
        registry.add("storage.minio.secret-key", () -> "acceptance-test-only");
        registry.add("storage.minio.bucket", () -> "ai-real-acceptance");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers;
    @Test void servesRealAcceptanceUntilStopMarker() throws Exception {
        var properties = AcceptanceDatabaseSupport.localProperties();
        UUID owner = jdbc.queryForObject("select id from app_user where username=?",UUID.class,
                properties.getProperty("DEMO_OWNER_USERNAME","owner"));
        UUID zen = jdbc.queryForObject("select id from user_ai_provider where user_id=? and preset_code='OPENCODE_ZEN_FREE' and enabled order by created_at limit 1",UUID.class,owner);
        providers.setDefault(owner, zen);
        jdbc.update("update user_model_purpose_assignment set provider_id=? where user_id=?",zen,owner);
        Files.deleteIfExists(STOP);
        System.out.println("REAL_ACCEPTANCE_READY api=http://localhost:18080; real providers, isolated persistent MinIO, offline database copy only");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(90);
        while (!Files.exists(STOP) && System.nanoTime() < deadline) Thread.sleep(500);
    }
}
