package com.shitulelv.aicollab.infrastructure.ai.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ZenDisconnectIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static UserAiProviderRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new UserAiProviderRepository(jdbc);
    }

    private static UUID user(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "zen-" + tag + "-" + id.toString().substring(0, 8), "test-only-hash", "Zen " + tag);
        return id;
    }

    private static UUID provider(UUID user, String name, boolean def, String preset) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name,is_default,preset_code)"
                + " VALUES (?,?,?,?,?,?,?,?)", id, user, name, "OPENAI_COMPATIBLE",
                "https://opencode.ai/zen/v1", "mimo-v2.5-free", def, preset);
        return id;
    }

    private static void assign(UUID user, String purpose, UUID provider) {
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,?,?)",
                user, purpose, provider);
    }

    private static int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void disconnectDeletesOwnZenCascadesAssignmentsAndPromotesNothing() {
        UUID userA = user("a");
        UUID userB = user("b");
        UUID zenA = provider(userA, "OpenCode Zen", true, "OPENCODE_ZEN_FREE");
        UUID customA = provider(userA, "Custom", false, null);
        UUID zenB = provider(userB, "OpenCode Zen", true, "OPENCODE_ZEN_FREE");
        assign(userA, "KNOWLEDGE_CHAT", zenA);
        assign(userB, "PLANNING", zenB);

        int deleted = repository.deleteByUserAndPreset(userA, "OPENCODE_ZEN_FREE");

        assertThat(deleted).isOne();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", zenA)).isZero();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", zenB)).isOne();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", customA)).isOne();
        assertThat(count("SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=?", userA)).isZero();
        assertThat(count("SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=?", userB)).isOne();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE user_id=? AND is_default", userA)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_default FROM user_ai_provider WHERE id=?", Boolean.class, customA)).isFalse();
    }

    @Test
    void disconnectMissingConnectionIsIdempotent() {
        UUID user = user("ghost");
        assertThat(repository.deleteByUserAndPreset(user, "OPENCODE_ZEN_FREE")).isZero();
    }
}
