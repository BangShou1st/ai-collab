package com.shitulelv.aicollab.infrastructure.ai.embedding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SystemEmbeddingConfigRepository {
    private static final RowMapper<SystemEmbeddingConfig> MAPPER = (ResultSet rs, int row) -> {
        try {
            return new SystemEmbeddingConfig(
                    rs.getObject("id", UUID.class),
                    rs.getString("provider"),
                    rs.getString("base_url"),
                    rs.getString("api_path"),
                    rs.getString("encrypted_api_key"),
                    rs.getString("model_name"),
                    rs.getInt("dimensions"),
                    rs.getInt("batch_size"),
                    rs.getString("fingerprint"),
                    rs.getBoolean("enabled"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map system embedding config", exception);
        }
    };
    private static final String COLUMNS = """
            id, provider, base_url, api_path, encrypted_api_key, model_name, dimensions,
            batch_size, fingerprint, enabled, created_at, updated_at""";

    private final JdbcTemplate jdbc;

    public SystemEmbeddingConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SystemEmbeddingConfig> findActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM system_embedding_config WHERE enabled LIMIT 1",
                MAPPER).stream().findFirst();
    }

    public SystemEmbeddingConfig save(SystemEmbeddingConfig value) {
        jdbc.update("""
                INSERT INTO system_embedding_config (
                    id, provider, base_url, api_path, encrypted_api_key, model_name, dimensions,
                    batch_size, fingerprint, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    api_path = EXCLUDED.api_path,
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    model_name = EXCLUDED.model_name,
                    dimensions = EXCLUDED.dimensions,
                    batch_size = EXCLUDED.batch_size,
                    fingerprint = EXCLUDED.fingerprint,
                    enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                value.id(), value.provider(), value.baseUrl(), value.apiPath(),
                value.encryptedApiKey(), value.modelName(), value.dimensions(), value.batchSize(),
                value.fingerprint(), value.enabled(), value.createdAt(), value.updatedAt());
        return findById(value.id()).orElseThrow();
    }

    public Optional<SystemEmbeddingConfig> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM system_embedding_config WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public void disableAll() {
        jdbc.update("UPDATE system_embedding_config SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP "
                + "WHERE enabled");
    }
}
