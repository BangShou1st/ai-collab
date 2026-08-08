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
public class ProjectEmbeddingConfigRepository {
    private final JdbcTemplate jdbc;

    public ProjectEmbeddingConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProjectEmbeddingConfig> findByProjectId(UUID projectId) {
        return jdbc.query("""
                SELECT project_id, provider, base_url, api_path, encrypted_api_key,
                       model_name, dimensions, batch_size, enabled, created_at, updated_at
                FROM project_embedding_config WHERE project_id = ?
                """, MAPPER, projectId).stream().findFirst();
    }

    public ProjectEmbeddingConfig save(ProjectEmbeddingConfig config) {
        jdbc.update("""
                INSERT INTO project_embedding_config (
                    project_id, provider, base_url, api_path, encrypted_api_key,
                    model_name, dimensions, batch_size, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    api_path = EXCLUDED.api_path,
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    model_name = EXCLUDED.model_name,
                    dimensions = EXCLUDED.dimensions,
                    batch_size = EXCLUDED.batch_size,
                    enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                config.projectId(), config.provider(), config.baseUrl(), config.apiPath(),
                config.encryptedApiKey(), config.modelName(), config.dimensions(),
                config.batchSize(), config.enabled(), config.createdAt(), config.updatedAt());
        return findByProjectId(config.projectId()).orElseThrow();
    }

    private static final RowMapper<ProjectEmbeddingConfig> MAPPER =
            new RowMapper<>() {
                @Override
                public ProjectEmbeddingConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new ProjectEmbeddingConfig(
                            rs.getObject("project_id", UUID.class),
                            rs.getString("provider"),
                            rs.getString("base_url"),
                            rs.getString("api_path"),
                            rs.getString("encrypted_api_key"),
                            rs.getString("model_name"),
                            rs.getInt("dimensions"),
                            rs.getInt("batch_size"),
                            rs.getBoolean("enabled"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class));
                }
            };
}
