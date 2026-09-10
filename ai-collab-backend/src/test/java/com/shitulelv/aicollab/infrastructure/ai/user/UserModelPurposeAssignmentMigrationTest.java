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
class UserModelPurposeAssignmentMigrationTest {
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

    @Test
    void v40_creates_assignment_table() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='40' AND success", Integer.class);
        assertThat(applied).isOne();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "uas-" + user.toString().substring(0, 8), "test-only-hash", "Assign");
        UUID provider = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name)
                VALUES (?,?,?,?,?,?)
                """, provider, user, "main", "OPENAI_COMPATIBLE", "https://api.openai.com",
                "gpt-4o-mini");
        jdbc.update("""
                INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id)
                VALUES (?,?,?)
                """, user, "KNOWLEDGE_CHAT", provider);
        assertThat(jdbc.queryForObject(
                "SELECT provider_id FROM user_model_purpose_assignment WHERE user_id=? AND purpose=?",
                UUID.class, user, "KNOWLEDGE_CHAT")).isEqualTo(provider);
    }
}
