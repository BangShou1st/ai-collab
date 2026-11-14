package com.shitulelv.aicollab.infrastructure.ai.capability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CapabilityProfileRepository {

    private static final RowMapper<CapabilityProfile> MAPPER = (rs, row) -> new CapabilityProfile(
            rs.getObject("id", UUID.class),
            rs.getObject("model_profile_id", UUID.class),
            rs.getObject("provider_id", UUID.class),
            CapabilityType.valueOf(rs.getString("capability")),
            rs.getString("protocol"),
            rs.getString("settings_json"),
            CapabilityProfileStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("source"),
            rs.getObject("verified_at", OffsetDateTime.class),
            rs.getObject("verified_by", UUID.class),
            rs.getObject("created_by", UUID.class),
            rs.getObject("updated_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, model_profile_id, provider_id, capability, protocol, settings_json::text,
                   status, version, source, verified_at, verified_by, created_by, updated_by,
                   created_at, updated_at
            FROM ai_model_capability_profile""";

    private final JdbcTemplate jdbc;

    public CapabilityProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CapabilityProfile create(UUID actorId, UUID modelProfileId, UUID providerId,
            CapabilityType capability, String protocol, String settingsJson, int version, String source) {
        return jdbc.queryForObject("""
                INSERT INTO ai_model_capability_profile (
                    model_profile_id, provider_id, capability, protocol, settings_json,
                    status, version, source, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?, ?, ?)
                RETURNING id, model_profile_id, provider_id, capability, protocol,
                    settings_json::text, status, version, source, verified_at, verified_by,
                    created_by, updated_by, created_at, updated_at
                """,
                MAPPER, modelProfileId, providerId, capability.name(), protocol, settingsJson,
                version, source, actorId, actorId);
    }

    public Optional<CapabilityProfile> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE id=?", MAPPER, id).stream().findFirst();
    }

    public Optional<CapabilityProfile> findByIdForUpdate(UUID id) {
        return jdbc.query(SELECT + " WHERE id=? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public List<CapabilityProfile> findAll() {
        return jdbc.query(SELECT + " ORDER BY updated_at DESC", MAPPER);
    }

    public int nextVersion(UUID modelProfileId, CapabilityType capability) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM ai_model_capability_profile
                WHERE model_profile_id=? AND capability=?
                """, Integer.class, modelProfileId, capability.name());
        return next == null ? 1 : next;
    }

    public Optional<CapabilityProfile> findPublishedForUpdate(UUID modelProfileId, CapabilityType capability) {
        return jdbc.query(SELECT + """
                 WHERE model_profile_id=? AND capability=? AND status='PUBLISHED'
                 FOR UPDATE
                """, MAPPER, modelProfileId, capability.name()).stream().findFirst();
    }

    public boolean markTested(UUID id, UUID actorId) {
        return jdbc.update("""
                UPDATE ai_model_capability_profile
                SET status='TESTED', verified_at=now(), verified_by=?, updated_by=?, updated_at=now()
                WHERE id=? AND status IN ('DRAFT', 'TESTED')
                """, actorId, actorId, id) == 1;
    }

    public boolean markPublished(UUID id, UUID actorId) {
        return jdbc.update("""
                UPDATE ai_model_capability_profile
                SET status='PUBLISHED', updated_by=?, updated_at=now()
                WHERE id=? AND status='TESTED' AND verified_at IS NOT NULL
                """, actorId, id) == 1;
    }

    public boolean markDisabled(UUID id, UUID actorId) {
        return jdbc.update("""
                UPDATE ai_model_capability_profile
                SET status='DISABLED', updated_by=?, updated_at=now()
                WHERE id=? AND status <> 'DISABLED'
                """, actorId, id) == 1;
    }

    /** 把当前 PUBLISHED 版本降为 DISABLED；先执行以避开部分唯一索引冲突。 */
    public boolean demotePublished(UUID id, UUID actorId) {
        return jdbc.update("""
                UPDATE ai_model_capability_profile
                SET status='DISABLED', updated_by=?, updated_at=now()
                WHERE id=? AND status='PUBLISHED'
                """, actorId, id) == 1;
    }

    public boolean publishRollbackTarget(UUID id, UUID actorId) {
        return jdbc.update("""
                UPDATE ai_model_capability_profile
                SET status='PUBLISHED', updated_by=?, updated_at=now()
                WHERE id=? AND status='DISABLED' AND verified_at IS NOT NULL
                """, actorId, id) == 1;
    }

    /** 回滚目标：低于当前版本、曾测试/发布且带核实时间的最近版本。 */
    public Optional<CapabilityProfile> findPreviousVerifiedForUpdate(UUID modelProfileId,
            CapabilityType capability, int currentVersion) {
        return jdbc.query(SELECT + """
                 WHERE model_profile_id=? AND capability=? AND version < ?
                   AND status='DISABLED' AND verified_at IS NOT NULL
                 ORDER BY version DESC
                 LIMIT 1
                 FOR UPDATE
                """, MAPPER, modelProfileId, capability.name(), currentVersion).stream().findFirst();
    }
}
