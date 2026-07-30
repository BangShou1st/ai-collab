package com.shitulelv.aicollab.infrastructure.ai.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class ModelConfigurationRepository {
    private final JdbcTemplate jdbc;

    public ModelConfigurationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelConfiguration> findAll() {
        return jdbc.query("""
                SELECT id, name, provider_type, base_url, api_path, encrypted_api_key,
                       model_name, enabled, temperature, max_output_tokens, capabilities,
                       created_at, updated_at
                FROM model_configuration
                ORDER BY name
                """, MAPPER);
    }

    public Optional<ModelConfiguration> findById(UUID id) {
        return jdbc.query("""
                SELECT id, name, provider_type, base_url, api_path, encrypted_api_key,
                       model_name, enabled, temperature, max_output_tokens, capabilities,
                       created_at, updated_at
                FROM model_configuration WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<ModelConfiguration> findAssigned(ModelPurpose purpose) {
        return jdbc.query("""
                SELECT c.id, c.name, c.provider_type, c.base_url, c.api_path, c.encrypted_api_key,
                       c.model_name, c.enabled, c.temperature, c.max_output_tokens, c.capabilities,
                       c.created_at, c.updated_at
                FROM model_purpose_assignment a
                JOIN model_configuration c ON c.id = a.model_configuration_id
                WHERE a.purpose = ?
                """, MAPPER, purpose.name()).stream().findFirst();
    }

    public ModelConfiguration save(ModelConfiguration value) {
        jdbc.update("""
                INSERT INTO model_configuration (
                    id, name, provider_type, base_url, api_path, encrypted_api_key,
                    model_name, enabled, temperature, max_output_tokens, capabilities,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    provider_type = EXCLUDED.provider_type,
                    base_url = EXCLUDED.base_url,
                    api_path = EXCLUDED.api_path,
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    model_name = EXCLUDED.model_name,
                    enabled = EXCLUDED.enabled,
                    temperature = EXCLUDED.temperature,
                    max_output_tokens = EXCLUDED.max_output_tokens,
                    capabilities = EXCLUDED.capabilities,
                    updated_at = EXCLUDED.updated_at
                """,
                value.id(), value.name(), value.providerType().name(), value.baseUrl(), value.apiPath(),
                value.encryptedApiKey(), value.modelName(), value.enabled(), value.temperature(),
                value.maxOutputTokens(), capabilityText(value.capabilities()),
                value.createdAt(), value.updatedAt());
        return findById(value.id()).orElseThrow();
    }

    public void assign(ModelPurpose purpose, UUID configurationId) {
        jdbc.update("""
                INSERT INTO model_purpose_assignment (purpose, model_configuration_id, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (purpose) DO UPDATE SET
                    model_configuration_id = EXCLUDED.model_configuration_id,
                    updated_at = CURRENT_TIMESTAMP
                """, purpose.name(), configurationId);
    }

    public List<ModelAssignment> assignments() {
        return jdbc.query("""
                SELECT purpose, model_configuration_id
                FROM model_purpose_assignment ORDER BY purpose
                """, (rs, row) -> new ModelAssignment(
                ModelPurpose.valueOf(rs.getString("purpose")),
                rs.getObject("model_configuration_id", UUID.class)));
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM model_configuration WHERE id = ?", id);
    }

    private static String capabilityText(EnumSet<ModelCapability> capabilities) {
        return capabilities.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static EnumSet<ModelCapability> parseCapabilities(String value) {
        EnumSet<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
        if (value != null && !value.isBlank()) {
            Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .map(ModelCapability::valueOf)
                    .forEach(result::add);
        }
        return result;
    }

    private static final RowMapper<ModelConfiguration> MAPPER =
            new RowMapper<>() {
                @Override
                public ModelConfiguration mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new ModelConfiguration(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            ModelProviderType.valueOf(rs.getString("provider_type")),
                            rs.getString("base_url"),
                            rs.getString("api_path"),
                            rs.getString("encrypted_api_key"),
                            rs.getString("model_name"),
                            rs.getBoolean("enabled"),
                            rs.getDouble("temperature"),
                            rs.getInt("max_output_tokens"),
                            parseCapabilities(rs.getString("capabilities")),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class));
                }
            };

    public record ModelAssignment(ModelPurpose purpose, UUID configurationId) {
    }
}
