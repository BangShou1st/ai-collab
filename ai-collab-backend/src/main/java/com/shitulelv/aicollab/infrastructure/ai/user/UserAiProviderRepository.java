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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class UserAiProviderRepository {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UserAiProviderRepository.class);
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
                    rs.getBoolean("shared"),
                    rs.getLong("capability_config_version"));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map user AI provider", exception);
        }
    };
    private static final String COLUMNS = """
            id, user_id, name, provider_type, base_url, api_path, encrypted_api_key,
            model_name, enabled, temperature, max_output_tokens, capabilities,
            is_default, created_at, updated_at, FALSE AS shared, capability_config_version""";

    private final JdbcTemplate jdbc;

    /**
     * 判定「影响真实模型请求行为的参数」是否变化：模型类型/端点/凭证/模型 ID/温度/输出上限。
     * 显示名称、启用开关、能力声明、默认标记不属于请求行为，不推进能力配置版本。
     */
    private static final String REQUEST_PARAMS_CHANGED = """
             user_ai_provider.provider_type IS DISTINCT FROM EXCLUDED.provider_type
                OR user_ai_provider.base_url IS DISTINCT FROM EXCLUDED.base_url
                OR user_ai_provider.api_path IS DISTINCT FROM EXCLUDED.api_path
                OR user_ai_provider.encrypted_api_key IS DISTINCT FROM EXCLUDED.encrypted_api_key
                OR user_ai_provider.model_name IS DISTINCT FROM EXCLUDED.model_name
                OR user_ai_provider.temperature IS DISTINCT FROM EXCLUDED.temperature
                OR user_ai_provider.max_output_tokens IS DISTINCT FROM EXCLUDED.max_output_tokens
            """;

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
        List<Map<String, Object>> assignments = jdbc.queryForList("""
                SELECT provider_id, COALESCE(is_shared, false) AS is_shared
                FROM user_model_purpose_assignment
                WHERE user_id = ? AND purpose = ?
                """, userId, purpose.name());
        if (assignments.isEmpty()) return Optional.empty();

        Map<String, Object> assignment = assignments.get(0);
        UUID providerId = (UUID) assignment.get("provider_id");
        boolean isShared = Boolean.TRUE.equals(assignment.get("is_shared"));

        if (isShared) {
            return jdbc.query("""
                    SELECT id, publisher_user_id AS user_id, name, provider_type, base_url, api_path,
                           encrypted_api_key, model_name, (enabled AND status = 'PUBLISHED') AS enabled,
                           temperature, max_output_tokens, capabilities, false AS is_default,
                           created_at, updated_at, TRUE AS shared, capability_config_version
                    FROM shared_ai_provider
                    WHERE id = ? AND status = 'PUBLISHED' AND enabled = true
                    """, MAPPER, providerId).stream().findFirst();
        }

        return jdbc.query("""
                SELECT p.id, p.user_id, p.name, p.provider_type, p.base_url, p.api_path,
                       p.encrypted_api_key, p.model_name, p.enabled, p.temperature,
                       p.max_output_tokens, p.capabilities, p.is_default, p.created_at, p.updated_at,
                       FALSE AS shared, p.capability_config_version
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
                    is_default, created_at, updated_at, capability_config_version
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
                    capability_config_version = CASE WHEN """ + REQUEST_PARAMS_CHANGED + """
                        THEN user_ai_provider.capability_config_version + 1
                        ELSE user_ai_provider.capability_config_version END,
                    updated_at = CASE WHEN """ + REQUEST_PARAMS_CHANGED + """
                        THEN CURRENT_TIMESTAMP
                        ELSE user_ai_provider.updated_at END
                """,
                value.id(), value.userId(), value.name(), value.providerType().name(),
                value.baseUrl(), value.apiPath(), value.encryptedApiKey(), value.modelName(),
                value.enabled(), value.temperature(), value.maxOutputTokens(),
                capabilityText(value.capabilities()), value.isDefault(),
                value.createdAt(), value.updatedAt(), value.capabilityConfigVersion());
        return findById(value.id()).orElseThrow();
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
        assignPurpose(userId, purpose, providerId, false);
    }

    public void assignPurpose(UUID userId, ModelPurpose purpose, UUID providerId, boolean isShared) {
        jdbc.update("""
                INSERT INTO user_model_purpose_assignment (user_id, purpose, provider_id, is_shared, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, purpose) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id,
                    is_shared = EXCLUDED.is_shared,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, purpose.name(), providerId, isShared);
    }

    public record PurposeAssignmentDetail(UUID providerId, boolean isShared) {}

    public java.util.Map<ModelPurpose, PurposeAssignmentDetail> listAssignmentDetails(UUID userId) {
        java.util.Map<ModelPurpose, PurposeAssignmentDetail> out = new java.util.EnumMap<>(ModelPurpose.class);
        jdbc.query("SELECT purpose, provider_id, COALESCE(is_shared, false) AS is_shared FROM user_model_purpose_assignment WHERE user_id = ?",
                (rs, row) -> {
                    try {
                        out.put(ModelPurpose.valueOf(rs.getString("purpose")),
                                new PurposeAssignmentDetail(
                                        rs.getObject("provider_id", UUID.class),
                                        rs.getBoolean("is_shared")));
                    } catch (IllegalArgumentException failure) {
                        log.warn("Skipping unmapped ModelPurpose value: {}", failure.getMessage());
                    }
                    return null;
                }, userId);
        return out;
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
                    } catch (IllegalArgumentException failure) {
                        // 库中出现无法映射的 purpose 枚举值时跳过该行，但记录告警以便排查脏数据
                        log.warn("Skipping unmapped ModelPurpose value in user_model_purpose_assignment: {}",
                                failure.getMessage());
                    }
                    return null;
                }, userId);
        return out;
    }

    public void deleteByIdAndUserId(UUID id, UUID userId) {
        jdbc.update("DELETE FROM user_ai_provider WHERE id = ? AND user_id = ?", id, userId);
    }

    /** 单条配置的价格视图（成本台账，设计文档 §4.4）；价格不影响真实请求行为，独立于聚合读取。 */
    public record ProviderPrices(java.math.BigDecimal pricePer1kInput, java.math.BigDecimal pricePer1kOutput) {}

    /** 读取单个配置的价格；行不存在时为空。 */
    public java.util.Optional<ProviderPrices> findPrices(UUID id, UUID userId) {
        return jdbc.query("""
                SELECT price_per_1k_input, price_per_1k_output
                FROM user_ai_provider WHERE id = ? AND user_id = ?
                """, (rs, row) -> new ProviderPrices(
                        rs.getBigDecimal("price_per_1k_input"),
                        rs.getBigDecimal("price_per_1k_output")), id, userId).stream().findFirst();
    }

    /** 读取用户全部配置的价格（列表视图一次取回，避免 N+1）。 */
    public java.util.Map<UUID, ProviderPrices> findPricesByUserId(UUID userId) {
        java.util.Map<UUID, ProviderPrices> out = new java.util.HashMap<>();
        jdbc.queryForList("""
                SELECT id, price_per_1k_input, price_per_1k_output
                FROM user_ai_provider WHERE user_id = ?
                """, userId).forEach(row -> out.put((UUID) row.get("id"), new ProviderPrices(
                        (java.math.BigDecimal) row.get("price_per_1k_input"),
                        (java.math.BigDecimal) row.get("price_per_1k_output"))));
        return out;
    }

    /**
     * 价格更新（成本台账，设计文档 §4.4）：走配置更新通道由服务层 require(userId, id)
     * 守卫后调用；整体替换语义（null = 不计成本）。刻意不动 updated_at 与
     * capability_config_version——价格不是影响真实模型请求行为的参数。
     */
    public void updatePrices(UUID id, UUID userId,
            java.math.BigDecimal pricePer1kInput, java.math.BigDecimal pricePer1kOutput) {
        jdbc.update("""
                UPDATE user_ai_provider
                SET price_per_1k_input = ?, price_per_1k_output = ?
                WHERE id = ? AND user_id = ?
                """, pricePer1kInput, pricePer1kOutput, id, userId);
    }

    /**
     * 探针写回的乐观并发（CAS）更新：仅当配置仍停留在探针开始时的版本
     * （能力配置版本未变）时追加能力，返回新的能力配置版本。
     *
     * <p>版本不匹配（检测期间改了模型/Base URL/Key/能力声明，或另一窗口并发修改）
     * 返回空——旧探针结论不得覆盖新配置。合并采用数组并集去重，
     * 不触碰用户保留的其他能力声明。</p>
     */
    public Optional<Long> addCapabilitiesIfVersionMatches(
            UUID id, UUID userId, java.util.Set<ModelCapability> capabilitiesToAdd, long expectedConfigVersion) {
        List<String> merged = capabilitiesToAdd.stream().map(Enum::name).toList();
        List<Long> updated = jdbc.query("""
                UPDATE user_ai_provider SET
                    capabilities = (
                        SELECT string_agg(DISTINCT cap, ',' ORDER BY cap)
                        FROM unnest(string_to_array(COALESCE(capabilities, ''), ',') || ?::text[]) AS cap
                        WHERE cap <> ''
                    ),
                    capability_config_version = capability_config_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND capability_config_version = ?
                RETURNING capability_config_version
                """,
                (rs, row) -> rs.getLong("capability_config_version"),
                merged.toArray(String[]::new), id, userId, expectedConfigVersion);
        return updated.stream().findFirst();
    }

    /**
     * 写回成功后，把本次运行落库的探针结论的版本锚点迁移到新配置版本，
     * 避免「刚探针通过并写回，结论立即被判 STALE」。只迁移仍锚定在旧版本上的
     * 行（检测期间被其他结论覆盖的行不受影响），且仅限本次运行覆盖的模式。
     */
    public void rebaseProbeTestedVersion(
            UUID providerId, long oldConfigVersion, long newConfigVersion, List<String> modes) {
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        named.update(
                "UPDATE user_ai_provider_probe_result SET tested_config_version = :newVersion "
                        + "WHERE provider_id = :providerId AND tested_config_version = :oldVersion "
                        + "AND probe_mode IN (:modes)",
                Map.of("newVersion", newConfigVersion, "providerId", providerId,
                        "oldVersion", oldConfigVersion, "modes", modes));
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
