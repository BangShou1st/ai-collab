package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class SharedAiProviderRepository {
    private static final Logger log = LoggerFactory.getLogger(SharedAiProviderRepository.class);

    private static final RowMapper<SharedAiProvider> MAPPER = (ResultSet rs, int row) -> {
        try {
            return new SharedAiProvider(
                    rs.getObject("id", UUID.class),
                    rs.getObject("source_provider_id", UUID.class),
                    rs.getObject("publisher_user_id", UUID.class),
                    rs.getString("name"),
                    ModelProviderType.valueOf(rs.getString("provider_type")),
                    rs.getString("base_url"),
                    rs.getString("api_path"),
                    rs.getString("encrypted_api_key"),
                    rs.getString("model_name"),
                    rs.getBoolean("enabled"),
                    rs.getString("status"),
                    rs.getDouble("temperature"),
                    rs.getInt("max_output_tokens"),
                    parseCapabilities(rs.getString("capabilities")),
                    rs.getLong("capability_config_version"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("published_at", OffsetDateTime.class));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map shared AI provider", exception);
        }
    };

    private static final String COLUMNS = """
            id, source_provider_id, publisher_user_id, name, provider_type, base_url, api_path,
            encrypted_api_key, model_name, enabled, status, temperature, max_output_tokens,
            capabilities, capability_config_version, created_at, updated_at, published_at""";

    private final JdbcTemplate jdbc;

    public SharedAiProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子保存或更新共享供应商。
     * 基于 source_provider_id 唯一约束实现并发幂等：重复发布只会更新配置版本与字段，不产生重复记录。
     */
    public SharedAiProvider saveOrUpdate(SharedAiProvider value) {
        jdbc.update("""
                INSERT INTO shared_ai_provider (
                    id, source_provider_id, publisher_user_id, name, provider_type,
                    base_url, api_path, encrypted_api_key, model_name, enabled,
                    status, temperature, max_output_tokens, capabilities,
                    capability_config_version, created_at, updated_at, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_provider_id) DO UPDATE SET
                    publisher_user_id = EXCLUDED.publisher_user_id,
                    name = EXCLUDED.name,
                    provider_type = EXCLUDED.provider_type,
                    base_url = EXCLUDED.base_url,
                    api_path = EXCLUDED.api_path,
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    model_name = EXCLUDED.model_name,
                    enabled = EXCLUDED.enabled,
                    status = EXCLUDED.status,
                    temperature = EXCLUDED.temperature,
                    max_output_tokens = EXCLUDED.max_output_tokens,
                    capabilities = EXCLUDED.capabilities,
                    capability_config_version = shared_ai_provider.capability_config_version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    published_at = CURRENT_TIMESTAMP
                """,
                value.id(), value.sourceProviderId(), value.publisherUserId(), value.name(),
                value.providerType().name(), value.baseUrl(), value.apiPath(), value.encryptedApiKey(),
                value.modelName(), value.enabled(), value.status(), value.temperature(),
                value.maxOutputTokens(), capabilityText(value.capabilities()),
                value.capabilityConfigVersion(), value.createdAt(), value.updatedAt(), value.publishedAt());

        return findBySourceProviderId(value.sourceProviderId()).orElseThrow();
    }

    public Optional<SharedAiProvider> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /** Chat 用途解析不得看到 LOCAL_CUSTOM 能力 Provider（RERANK 等非 Chat 能力）。 */
    public Optional<SharedAiProvider> findChatCapableById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider"
                + " WHERE id = ? AND provider_type <> 'LOCAL_CUSTOM'",
                MAPPER, id).stream().findFirst();
    }

    public Optional<SharedAiProvider> findBySourceProviderId(UUID sourceProviderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider WHERE source_provider_id = ?",
                MAPPER, sourceProviderId).stream().findFirst();
    }

    public List<SharedAiProvider> findAllPublished() {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider"
                        + " WHERE status = 'PUBLISHED' AND enabled = true"
                        + " AND provider_type <> 'LOCAL_CUSTOM'"
                        + " ORDER BY published_at DESC",
                MAPPER);
    }

    public List<SharedAiProvider> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider ORDER BY updated_at DESC",
                MAPPER);
    }

    public int revokeBySourceProviderId(UUID sourceProviderId) {
        return jdbc.update("UPDATE shared_ai_provider SET status = 'REVOKED', updated_at = CURRENT_TIMESTAMP WHERE source_provider_id = ?",
                sourceProviderId);
    }

    public int revokeById(UUID id) {
        return jdbc.update("UPDATE shared_ai_provider SET status = 'REVOKED', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    public int deleteBySourceProviderId(UUID sourceProviderId) {
        return jdbc.update("DELETE FROM shared_ai_provider WHERE source_provider_id = ?", sourceProviderId);
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
