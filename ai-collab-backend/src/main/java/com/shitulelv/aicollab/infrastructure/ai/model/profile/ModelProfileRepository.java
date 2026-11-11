package com.shitulelv.aicollab.infrastructure.ai.model.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 全局模型档案的 SQL 访问。档案按 {@code profile_key} 唯一，不按用户隔离。 */
@Repository
public class ModelProfileRepository {
    private final JdbcTemplate jdbc;

    public ModelProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelProfile> list() {
        return jdbc.query("SELECT * FROM ai_model_profile ORDER BY provider_type, model_name", mapper());
    }

    public Optional<ModelProfile> find(UUID id) {
        return jdbc.query("SELECT * FROM ai_model_profile WHERE id=?", mapper(), id).stream().findFirst();
    }

    public Optional<ModelProfile> findByKey(String profileKey) {
        return jdbc.query("SELECT * FROM ai_model_profile WHERE profile_key=?", mapper(), profileKey)
                .stream().findFirst();
    }

    public ModelProfile create(UUID actorId, String profileKey, String providerType, String baseUrl,
                               String apiPath, String modelName, int contextWindowTokens,
                               Integer maxOutputTokens, boolean supportsUsage, String tokenizerKind,
                               String pricingVersion, String source, OffsetDateTime verifiedAt, UUID verifiedBy) {
        return jdbc.queryForObject("""
                INSERT INTO ai_model_profile(profile_key, provider_type, base_url, api_path, model_name,
                    context_window_tokens, max_output_tokens, supports_usage, tokenizer_kind, pricing_version,
                    source, verified_at, verified_by, created_by, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, mapper(), profileKey, providerType, baseUrl, apiPath, modelName,
                contextWindowTokens, maxOutputTokens, supportsUsage, tokenizerKind, pricingVersion,
                source, verifiedAt, verifiedBy, actorId, actorId);
    }

    public boolean update(UUID id, UUID actorId, String profileKey, String providerType, String baseUrl,
                          String apiPath, String modelName, int contextWindowTokens,
                          Integer maxOutputTokens, boolean supportsUsage, String tokenizerKind,
                          String pricingVersion, String source, OffsetDateTime verifiedAt, UUID verifiedBy) {
        return jdbc.update("""
                UPDATE ai_model_profile SET profile_key=?, provider_type=?, base_url=?, api_path=?, model_name=?,
                    context_window_tokens=?, max_output_tokens=?, supports_usage=?, tokenizer_kind=?, pricing_version=?,
                    source=?, verified_at=?, verified_by=?, updated_by=?, updated_at=now()
                WHERE id=?
                """, profileKey, providerType, baseUrl, apiPath, modelName,
                contextWindowTokens, maxOutputTokens, supportsUsage, tokenizerKind, pricingVersion,
                source, verifiedAt, verifiedBy, actorId, id) == 1;
    }

    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM ai_model_profile WHERE id=?", id) == 1;
    }

    private static RowMapper<ModelProfile> mapper() {
        return (rs, row) -> new ModelProfile(
                rs.getObject("id", UUID.class),
                rs.getString("provider_type"),
                rs.getString("base_url"),
                rs.getString("api_path"),
                rs.getString("model_name"),
                rs.getInt("context_window_tokens"),
                rs.getObject("max_output_tokens", Integer.class),
                rs.getBoolean("supports_usage"),
                rs.getString("tokenizer_kind"),
                rs.getString("pricing_version"),
                ModelProfileSource.valueOf(rs.getString("source")),
                rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("verified_by", UUID.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
