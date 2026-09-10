package com.shitulelv.aicollab.infrastructure.ai.embedding;

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
class SystemEmbeddingMigrationTest {
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
    void v41_creates_system_embedding_table() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='41' AND success", Integer.class);
        assertThat(applied).isOne();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,model_name,dimensions,fingerprint)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), "OPENAI_COMPATIBLE", "https://api.openai.com",
                "text-embedding-3-small", 1536, "sha-test");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_embedding_config", Integer.class)).isOne();
    }
}
