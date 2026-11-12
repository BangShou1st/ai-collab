package com.shitulelv.aicollab.infrastructure.ai.capability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CapabilityAssignmentRepository {

    private static final RowMapper<CapabilityAssignment> MAPPER = (rs, row) -> new CapabilityAssignment(
            rs.getObject("id", UUID.class),
            CapabilityType.valueOf(rs.getString("capability")),
            rs.getString("purpose"),
            CapabilityAssignmentScope.valueOf(rs.getString("scope")),
            rs.getObject("project_id", UUID.class),
            rs.getObject("model_capability_profile_id", UUID.class),
            rs.getBoolean("enabled"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("updated_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, capability, purpose, scope, project_id, model_capability_profile_id,
                   enabled, created_by, updated_by, created_at, updated_at
            FROM ai_capability_assignment""";

    private final JdbcTemplate jdbc;

    public CapabilityAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CapabilityAssignment create(UUID actorId, CapabilityType capability, String purpose,
            CapabilityAssignmentScope scope, UUID projectId, UUID profileId, boolean enabled) {
        return jdbc.queryForObject("""
                INSERT INTO ai_capability_assignment (
                    capability, purpose, scope, project_id, model_capability_profile_id,
                    enabled, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, capability, purpose, scope, project_id, model_capability_profile_id,
                    enabled, created_by, updated_by, created_at, updated_at
                """, MAPPER, capability.name(), purpose, scope.name(), projectId, profileId,
                enabled, actorId, actorId);
    }

    public boolean update(UUID id, UUID actorId, UUID profileId, boolean enabled) {
        return jdbc.update("""
                UPDATE ai_capability_assignment
                SET model_capability_profile_id=?, enabled=?, updated_by=?, updated_at=now()
                WHERE id=?
                """, profileId, enabled, actorId, id) == 1;
    }

    public Optional<CapabilityAssignment> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE id=?", MAPPER, id).stream().findFirst();
    }

    public Optional<CapabilityAssignment> findGlobal(CapabilityType capability, String purpose) {
        return jdbc.query(SELECT + " WHERE scope='GLOBAL' AND project_id IS NULL AND capability=? AND purpose=?",
                MAPPER, capability.name(), purpose).stream().findFirst();
    }

    public Optional<CapabilityAssignment> findProject(UUID projectId, CapabilityType capability,
            String purpose) {
        return jdbc.query(SELECT + " WHERE scope='PROJECT' AND project_id=? AND capability=? AND purpose=?",
                MAPPER, projectId, capability.name(), purpose).stream().findFirst();
    }

    public Optional<CapabilityAssignment> findEnabledProject(UUID projectId, CapabilityType capability,
            String purpose) {
        return jdbc.query(SELECT + """
                 WHERE scope='PROJECT' AND project_id=? AND capability=? AND purpose=? AND enabled=true
                 ORDER BY updated_at DESC
                 LIMIT 1
                """, MAPPER, projectId, capability.name(), purpose).stream().findFirst();
    }

    public Optional<CapabilityAssignment> findEnabledGlobal(CapabilityType capability, String purpose) {
        return jdbc.query(SELECT + """
                 WHERE scope='GLOBAL' AND project_id IS NULL AND capability=? AND purpose=? AND enabled=true
                 LIMIT 1
                """, MAPPER, capability.name(), purpose).stream().findFirst();
    }

    public List<CapabilityAssignment> findAll() {
        return jdbc.query(SELECT + " ORDER BY scope, capability, purpose, updated_at DESC", MAPPER);
    }

    public List<CapabilityAssignment> findByProject(UUID projectId) {
        return jdbc.query(SELECT + " WHERE scope='PROJECT' AND project_id=? ORDER BY capability, purpose",
                MAPPER, projectId);
    }

    public int repointProfile(UUID oldProfileId, UUID newProfileId) {
        return jdbc.update("""
                UPDATE ai_capability_assignment
                SET model_capability_profile_id=?, updated_at=now()
                WHERE model_capability_profile_id=?
                """, newProfileId, oldProfileId);
    }

    public int disableForProfile(UUID profileId) {
        return jdbc.update("""
                UPDATE ai_capability_assignment
                SET enabled=false, updated_at=now()
                WHERE model_capability_profile_id=? AND enabled=true
                """, profileId);
    }

    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM ai_capability_assignment WHERE id=?", id) == 1;
    }
}
