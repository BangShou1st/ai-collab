package com.shitulelv.aicollab.infrastructure.ai.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 能力探针结果仓储：每个 (provider, probe_mode) 只保留最新一条。
 *
 * <p>{@code STALE} 不落库——读取时由服务层对比 {@code user_ai_provider.updated_at}
 * 与 {@code tested_config_updated_at} 动态判定，避免「配置改了但忘了作废旧结果」
 * 与「配置没改却定时过期」两类失真。</p>
 */
@Repository
public class UserAiProviderProbeResultRepository {
    static final String PASS = "PASS";
    static final String FAIL = "FAIL";
    static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    static final String INCONCLUSIVE = "INCONCLUSIVE";

    public record ProbeResultRow(
            UUID providerId,
            String mode,
            String status,
            String modelName,
            String detail,
            String errorCode,
            Long latencyMs,
            OffsetDateTime testedConfigUpdatedAt,
            OffsetDateTime testedAt) {
    }

    private static final RowMapper<ProbeResultRow> MAPPER = (ResultSet rs, int row) -> {
        try {
            Long latency = rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms");
            return new ProbeResultRow(
                    rs.getObject("provider_id", UUID.class),
                    rs.getString("probe_mode"),
                    rs.getString("status"),
                    rs.getString("model_name"),
                    rs.getString("detail"),
                    rs.getString("error_code"),
                    latency,
                    rs.getObject("tested_config_updated_at", OffsetDateTime.class),
                    rs.getObject("tested_at", OffsetDateTime.class));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map probe result", exception);
        }
    };

    private final JdbcTemplate jdbc;

    public UserAiProviderProbeResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(ProbeResultRow row) {
        jdbc.update("""
                INSERT INTO user_ai_provider_probe_result (
                    provider_id, probe_mode, status, model_name, detail, error_code,
                    latency_ms, tested_config_updated_at, tested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (provider_id, probe_mode) DO UPDATE SET
                    status = EXCLUDED.status,
                    model_name = EXCLUDED.model_name,
                    detail = EXCLUDED.detail,
                    error_code = EXCLUDED.error_code,
                    latency_ms = EXCLUDED.latency_ms,
                    tested_config_updated_at = EXCLUDED.tested_config_updated_at,
                    tested_at = EXCLUDED.tested_at
                """,
                row.providerId(), row.mode(), row.status(), row.modelName(), row.detail(),
                row.errorCode(), row.latencyMs(), row.testedConfigUpdatedAt());
    }

    public List<ProbeResultRow> findAllByProviderId(UUID providerId) {
        return jdbc.query(
                "SELECT provider_id, probe_mode, status, model_name, detail, error_code, "
                        + "latency_ms, tested_config_updated_at, tested_at "
                        + "FROM user_ai_provider_probe_result WHERE provider_id = ?",
                MAPPER, providerId);
    }

    public Optional<ProbeResultRow> find(UUID providerId, String mode) {
        return jdbc.query(
                "SELECT provider_id, probe_mode, status, model_name, detail, error_code, "
                        + "latency_ms, tested_config_updated_at, tested_at "
                        + "FROM user_ai_provider_probe_result WHERE provider_id = ? AND probe_mode = ?",
                MAPPER, providerId, mode).stream().findFirst();
    }
}
