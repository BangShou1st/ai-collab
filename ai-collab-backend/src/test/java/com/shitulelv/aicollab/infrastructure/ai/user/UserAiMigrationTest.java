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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class UserAiMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private static UUID newUser() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "uai-" + user.toString().substring(0, 8), "test-only-hash", "User AI");
        return user;
    }

    private static void insertProvider(UUID id, UUID user, String name, boolean isDefault) {
        jdbc.update("""
                INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name,is_default)
                VALUES (?,?,?,?,?,?,?)
                """, id, user, name, "OPENAI_COMPATIBLE", "https://api.openai.com",
                "gpt-4o-mini", isDefault);
    }

    @Test
    void v39_creates_user_ai_table() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='39' AND success", Integer.class);
        assertThat(applied).isOne();
        UUID user = newUser();
        UUID provider = UUID.randomUUID();
        insertProvider(provider, user, "main", true);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider WHERE user_id=?", Integer.class, user)).isOne();
    }

    @Test
    void user_cannot_have_two_default_providers() {
        UUID user = newUser();
        insertProvider(UUID.randomUUID(), user, "first", true);
        insertProvider(UUID.randomUUID(), user, "second", false);
        assertThatThrownBy(() -> insertProvider(UUID.randomUUID(), user, "third", true))
                .isInstanceOf(Exception.class);
    }
}
