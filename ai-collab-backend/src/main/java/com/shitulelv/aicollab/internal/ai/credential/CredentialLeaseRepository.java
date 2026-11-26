package com.shitulelv.aicollab.internal.ai.credential;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code public.ai_credential_lease} 审计表（V104）。只记谁、为何、借了哪个提供商的哪个模型、何时到期，
 * <b>不存任何密钥或密文</b>。
 */
@Repository
public class CredentialLeaseRepository {

    private final JdbcTemplate jdbc;

    public CredentialLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LeaseRecord lease) {
        jdbc.update("""
                INSERT INTO ai_credential_lease (id, user_id, project_id, purpose, provider_id,
                                                 provider_type, model, task_id, run_id, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lease.id(), lease.userId(), lease.projectId(), lease.purpose().name(),
                lease.providerId(), lease.providerType(), lease.model(), lease.taskId(), lease.runId(),
                lease.issuedAt(), lease.expiresAt());
    }

    public Optional<LeaseRecord> find(UUID id) {
        return jdbc.query("""
                SELECT id, user_id, project_id, purpose, provider_id, provider_type, model,
                       task_id, run_id, issued_at, expires_at
                  FROM ai_credential_lease WHERE id = ?
                """, (rs, row) -> new LeaseRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        LeasePurpose.valueOf(rs.getString("purpose")),
                        rs.getObject("provider_id", UUID.class),
                        rs.getString("provider_type"),
                        rs.getString("model"),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("issued_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)), id)
                .stream().findFirst();
    }

    /** 审计行（不含密钥）。 */
    public record LeaseRecord(
            UUID id,
            UUID userId,
            UUID projectId,
            LeasePurpose purpose,
            UUID providerId,
            String providerType,
            String model,
            UUID taskId,
            UUID runId,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt) {
    }
}
