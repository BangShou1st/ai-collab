package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 管理端共享 Provider 的能力字段访问；不复用个人 Provider 的运行时实体。 */
@Repository
public class CapabilityProviderRepository {

    private static final String COLUMNS = """
            id, name, provider_type, protocol, base_url, api_path, auth_type, network_zone,
            health_path, encrypted_api_key, enabled, status, created_at, updated_at, published_at""";

    private static final RowMapper<CapabilityProvider> MAPPER = (rs, row) -> new CapabilityProvider(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            ModelProviderType.valueOf(rs.getString("provider_type")),
            rs.getString("protocol"),
            rs.getString("base_url"),
            rs.getString("api_path"),
            rs.getString("auth_type"),
            rs.getString("network_zone"),
            rs.getString("health_path"),
            rs.getString("encrypted_api_key"),
            rs.getBoolean("enabled"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("published_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public CapabilityProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CapabilityProvider create(UUID actorId, UUID id, String name, ModelProviderType providerType,
            String protocol, String baseUrl, String apiPath, String authType, String networkZone,
            String healthPath, String encryptedApiKey, boolean enabled) {
        return jdbc.queryForObject("""
                INSERT INTO shared_ai_provider (
                    id, source_provider_id, publisher_user_id, name, provider_type, protocol,
                    base_url, api_path, encrypted_api_key, model_name, enabled, status,
                    temperature, max_output_tokens, capabilities, capability_config_version,
                    auth_type, network_zone, health_path
                ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'PUBLISHED', 0.7, 4096,
                    'RERANK', 0, ?, ?, ?)
                RETURNING
                """ + COLUMNS,
                MAPPER, id, actorId, name, providerType.name(), protocol, baseUrl, apiPath,
                encryptedApiKey, enabled, authType, networkZone, healthPath);
    }

    public boolean update(UUID id, UUID actorId, String name, ModelProviderType providerType,
            String protocol, String baseUrl, String apiPath, String authType, String networkZone,
            String healthPath, String encryptedApiKey, boolean enabled) {
        return jdbc.update("""
                UPDATE shared_ai_provider
                SET publisher_user_id=?, name=?, provider_type=?, protocol=?, base_url=?, api_path=?,
                    auth_type=?, network_zone=?, health_path=?, encrypted_api_key=?, enabled=?,
                    updated_at=now()
                WHERE id=?
                """, actorId, name, providerType.name(), protocol, baseUrl, apiPath, authType,
                networkZone, healthPath, encryptedApiKey, enabled, id) == 1;
    }

    public Optional<CapabilityProvider> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider WHERE id=?",
                MAPPER, id).stream().findFirst();
    }

    public List<CapabilityProvider> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider ORDER BY updated_at DESC",
                MAPPER);
    }

    public Optional<CapabilityProvider> findByLowerName(String name) {
        return jdbc.query("SELECT " + COLUMNS + " FROM shared_ai_provider WHERE lower(name)=lower(?)",
                MAPPER, name).stream().findFirst();
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM shared_ai_provider WHERE id=?", id);
    }
}
