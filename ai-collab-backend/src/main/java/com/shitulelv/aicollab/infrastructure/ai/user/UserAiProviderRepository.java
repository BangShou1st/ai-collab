package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
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
public class UserAiProviderRepository {
    private static final RowMapper<UserAiProvider> MAPPER = (ResultSet rs, int row) -> {
        try {
            return new UserAiProvider(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
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
                    rs.getBoolean("is_default"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    getPreset(rs));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map user AI provider", exception);
        }
    };
    private static final String COLUMNS = """
            id, user_id, name, provider_type, base_url, api_path, encrypted_api_key,
            model_name, enabled, temperature, max_output_tokens, capabilities,
            is_default, created_at, updated_at, preset_code""";
    private static String getPreset(ResultSet rs) {
        try { return rs.getString("preset_code"); }
        catch (Exception e) { return null; }
    }

    private final JdbcTemplate jdbc;

    public UserAiProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserAiProvider> findAllByUserId(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_ai_provider WHERE user_id = ? ORDER BY name",
                MAPPER, userId);
    }

    public Optional<UserAiProvider> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_ai_provider WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public Optional<UserAiProvider> findByIdAndUserId(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_ai_provider WHERE id = ? AND user_id = ?",
                MAPPER, id, userId).stream().findFirst();
    }

    public Optional<UserAiProvider> findDefaultByUserId(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_ai_provider WHERE user_id = ? AND is_default",
                MAPPER, userId).stream().findFirst();
    }

    public Optional<UserAiProvider> findAssigned(UUID userId, ModelPurpose purpose) {
        return jdbc.query("""
                SELECT p.id, p.user_id, p.name, p.provider_type, p.base_url, p.api_path,
                       p.encrypted_api_key, p.model_name, p.enabled, p.temperature,
                       p.max_output_tokens, p.capabilities, p.is_default, p.created_at, p.updated_at, p.preset_code
                FROM user_model_purpose_assignment a
                JOIN user_ai_provider p ON p.id = a.provider_id
                WHERE a.user_id = ? AND a.purpose = ?
                """, MAPPER, userId, purpose.name()).stream().findFirst();
    }

    public int countByUserId(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public UserAiProvider save(UserAiProvider value) {
        jdbc.update("""
                INSERT INTO user_ai_provider (
                    id, user_id, name, provider_type, base_url, api_path, encrypted_api_key,
                    model_name, enabled, temperature, max_output_tokens, capabilities,
                    is_default, created_at, updated_at, preset_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
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
                    is_default = EXCLUDED.is_default,
                    preset_code = EXCLUDED.preset_code,
                    updated_at = EXCLUDED.updated_at
                """,
                value.id(), value.userId(), value.name(), value.providerType().name(),
                value.baseUrl(), value.apiPath(), value.encryptedApiKey(), value.modelName(),
                value.enabled(), value.temperature(), value.maxOutputTokens(),
                capabilityText(value.capabilities()), value.isDefault(),
                value.createdAt(), value.updatedAt(), value.presetCode());
        return findById(value.id()).orElseThrow();
    }

    public Optional<UserAiProvider> findByUserAndPreset(UUID userId, String presetCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_ai_provider WHERE user_id = ? AND preset_code = ?",
                MAPPER, userId, presetCode).stream().findFirst();
    }

    public void clearDefault(UUID userId) {
        jdbc.update("UPDATE user_ai_provider SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND is_default", userId);
    }

    public int markDefault(UUID userId, UUID id) {
        return jdbc.update("UPDATE user_ai_provider SET is_default = TRUE, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND user_id = ?", id, userId);
    }

    public void assignPurpose(UUID userId, ModelPurpose purpose, UUID providerId) {
        jdbc.update("""
                INSERT INTO user_model_purpose_assignment (user_id, purpose, provider_id, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, purpose) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, purpose.name(), providerId);
    }

    public void unassignPurpose(UUID userId, ModelPurpose purpose) {
        jdbc.update("DELETE FROM user_model_purpose_assignment WHERE user_id = ? AND purpose = ?",
                userId, purpose.name());
    }

    public java.util.Map<ModelPurpose, UUID> listAssignments(UUID userId) {
        java.util.Map<ModelPurpose, UUID> out = new java.util.EnumMap<>(ModelPurpose.class);
        jdbc.query("SELECT purpose, provider_id FROM user_model_purpose_assignment WHERE user_id = ?",
                (rs, row) -> {
                    try {
                        out.put(ModelPurpose.valueOf(rs.getString("purpose")),
                                rs.getObject("provider_id", UUID.class));
                    } catch (IllegalArgumentException ignored) {
                    }
                    return null;
                }, userId);
        return out;
    }

    public void deleteByIdAndUserId(UUID id, UUID userId) {
        jdbc.update("DELETE FROM user_ai_provider WHERE id = ? AND user_id = ?", id, userId);
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
}
