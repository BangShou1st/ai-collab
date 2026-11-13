package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderProbeResultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V98 真实迁移：PUBLISH_ADMISSION 模式可通过既有探针结果表落库并读取。
 */
@Testcontainers(disabledWithoutDocker = true)
class ProviderAdmissionPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static UserAiProviderProbeResultRepository probeResults;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        probeResults = new UserAiProviderProbeResultRepository(jdbc);
    }

    @Test
    void publishAdmissionModeIsAcceptedAndUpserted() {
        UUID user = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,'test-only-hash','Admission User')
                """, user, "admission-" + user.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO user_ai_provider(
                    id,user_id,name,provider_type,base_url,model_name,capability_config_version
                ) VALUES (?,?,'admission','OPENAI_COMPATIBLE','https://api.example.com','fixture-chat',3)
                """, provider, user);

        String passing = """
                {"schemaVersion":1,"status":"PASS","samples":3,"p50Ms":10,"p95Ms":20,
                "timeoutCount":0,"failureCount":0,"configVersion":3,"p95LimitMs":10000}
                """;
        probeResults.upsert(new UserAiProviderProbeResultRepository.ProbeResultRow(
                provider,
                ProviderAdmissionSnapshot.MODE,
                ProviderAdmissionSnapshot.STATUS_PASS,
                "fixture-chat",
                passing,
                null,
                20L,
                OffsetDateTime.now(),
                3L,
                null));

        UserAiProviderProbeResultRepository.ProbeResultRow first = probeResults
                .find(provider, ProviderAdmissionSnapshot.MODE)
                .orElseThrow();
        assertThat(first.status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_PASS);
        assertThat(first.testedConfigVersion()).isEqualTo(3L);
        assertThat(first.latencyMs()).isEqualTo(20L);
        assertThat(ProviderAdmissionSnapshot.fromJson(
                readJson(first.detail())).acceptedBy(ProviderAdmissionProperties.defaults())).isTrue();

        probeResults.upsert(new UserAiProviderProbeResultRepository.ProbeResultRow(
                provider,
                ProviderAdmissionSnapshot.MODE,
                ProviderAdmissionSnapshot.STATUS_FAIL,
                "fixture-chat",
                passing.replace("\"PASS\"", "\"FAIL\""),
                "AI_MODEL_TIMEOUT",
                30L,
                OffsetDateTime.now(),
                3L,
                null));
        assertThat(probeResults.find(provider, ProviderAdmissionSnapshot.MODE)
                .orElseThrow().status()).isEqualTo(ProviderAdmissionSnapshot.STATUS_FAIL);
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
        } catch (Exception malformed) {
            throw new AssertionError("invalid admission JSON", malformed);
        }
    }
}
