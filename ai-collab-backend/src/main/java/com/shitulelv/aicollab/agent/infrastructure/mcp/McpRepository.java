package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class McpRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public McpRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<McpConnection> listByProject(UUID projectId) {
        return jdbc.query("SELECT * FROM agent_mcp_connection WHERE project_id=? ORDER BY code",
                connectionMapper(), projectId);
    }

    public Optional<McpConnection> find(UUID id) {
        return jdbc.query("SELECT * FROM agent_mcp_connection WHERE id=?", connectionMapper(), id)
                .stream().findFirst();
    }

    public McpConnection create(McpConnection value) {
        return jdbc.queryForObject("""
                INSERT INTO agent_mcp_connection(
                  id,project_id,code,name,transport,endpoint,stdio_command_json,auth_type,
                  credential_ciphertext,credential_key_version,timeout_ms,max_result_bytes,
                  tool_allowlist_json,resource_allowlist_json,created_by)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?::jsonb,?) RETURNING *
                """, connectionMapper(), value.id(), value.projectId(), value.code(), value.name(),
                value.transport().name(), value.endpoint(), value.stdioCommandJson(),
                value.authType().name(), value.credentialCiphertext(), value.credentialKeyVersion(),
                value.timeoutMs(), value.maxResultBytes(), value.toolAllowlistJson(),
                value.resourceAllowlistJson(), value.createdBy());
    }

    public boolean update(McpConnection value, int expectedVersion) {
        return jdbc.update("""
                UPDATE agent_mcp_connection SET code=?,name=?,transport=?,endpoint=?,
                  stdio_command_json=?::jsonb,auth_type=?,credential_ciphertext=?,
                  credential_key_version=?,timeout_ms=?,max_result_bytes=?,
                  tool_allowlist_json=?::jsonb,resource_allowlist_json=?::jsonb,
                  enabled=false,confirmed_schema_hash=NULL,version=version+1,updated_at=now()
                WHERE id=? AND version=?
                """, value.code(), value.name(), value.transport().name(), value.endpoint(),
                value.stdioCommandJson(), value.authType().name(), value.credentialCiphertext(),
                value.credentialKeyVersion(), value.timeoutMs(), value.maxResultBytes(),
                value.toolAllowlistJson(), value.resourceAllowlistJson(), value.id(), expectedVersion) == 1;
    }

    public boolean setEnabled(UUID id, int version, boolean enabled) {
        String confirmation = enabled ? "confirmed_schema_hash=schema_hash," : "";
        return jdbc.update("UPDATE agent_mcp_connection SET enabled=?," + confirmation
                + "version=version+1,updated_at=now() WHERE id=? AND version=?"
                + (enabled ? " AND schema_hash IS NOT NULL" : ""), enabled, id, version) == 1;
    }

    public Optional<McpProviderAdmission> findAdmission(UUID connectionId) {
        return jdbc.query("""
                SELECT * FROM agent_mcp_provider_admission WHERE connection_id=?
                """, admissionMapper(), connectionId).stream().findFirst();
    }

    public Optional<McpProviderAdmission> saveAdmissionDraft(McpProviderAdmission value,
            int expectedVersion) {
        return jdbc.query("""
                INSERT INTO agent_mcp_provider_admission(
                  connection_id,provider_type,provider_owner,data_scope,retention_policy,
                  terms_url,least_privilege_scope,read_only_endpoint,status,version)
                VALUES (?,?,?,?,?,?,?,?,'DRAFT',0)
                ON CONFLICT (connection_id) DO UPDATE SET
                  provider_type=EXCLUDED.provider_type,
                  provider_owner=EXCLUDED.provider_owner,
                  data_scope=EXCLUDED.data_scope,
                  retention_policy=EXCLUDED.retention_policy,
                  terms_url=EXCLUDED.terms_url,
                  least_privilege_scope=EXCLUDED.least_privilege_scope,
                  read_only_endpoint=EXCLUDED.read_only_endpoint,
                  status='DRAFT',
                  reviewed_by=NULL,
                  reviewed_at=NULL,
                  revoked_by=NULL,
                  revoked_at=NULL,
                  version=agent_mcp_provider_admission.version+1,
                  updated_at=now()
                WHERE agent_mcp_provider_admission.version=?
                RETURNING *
                """, admissionMapper(), value.connectionId(), value.providerType().name(),
                value.providerOwner(), value.dataScope(), value.retentionPolicy(),
                value.termsUrl(), value.leastPrivilegeScope(), value.readOnlyEndpoint(),
                expectedVersion).stream().findFirst();
    }

    public Optional<McpProviderAdmission> approveAdmission(UUID connectionId,
            int expectedVersion, UUID reviewer) {
        return jdbc.query("""
                UPDATE agent_mcp_provider_admission SET
                  status='APPROVED',reviewed_by=?,reviewed_at=now(),revoked_by=NULL,revoked_at=NULL,
                  version=version+1,updated_at=now()
                WHERE connection_id=? AND version=? AND status IN ('DRAFT','REVOKED')
                RETURNING *
                """, admissionMapper(), reviewer, connectionId, expectedVersion).stream().findFirst();
    }

    public Optional<McpProviderAdmission> revokeAdmission(UUID connectionId,
            int expectedVersion, UUID reviewer) {
        return jdbc.query("""
                UPDATE agent_mcp_provider_admission SET
                  status='REVOKED',revoked_by=?,revoked_at=now(),
                  version=version+1,updated_at=now()
                WHERE connection_id=? AND version=? AND status <> 'REVOKED'
                RETURNING *
                """, admissionMapper(), reviewer, connectionId, expectedVersion).stream().findFirst();
    }

    public void revokeAdmissionOnConnectionChange(UUID connectionId) {
        jdbc.update("""
                UPDATE agent_mcp_provider_admission SET
                  status='REVOKED',revoked_at=COALESCE(revoked_at,now()),
                  version=version+1,updated_at=now()
                WHERE connection_id=? AND status <> 'REVOKED'
                """, connectionId);
    }

    public void disable(UUID connectionId) {
        jdbc.update("""
                UPDATE agent_mcp_connection SET enabled=false,version=version+1,updated_at=now()
                WHERE id=?
                """, connectionId);
    }

    public void health(UUID id, String status, String message) {
        jdbc.update("""
                UPDATE agent_mcp_connection SET last_health_status=?,last_health_message=?,
                  last_health_at=now(),updated_at=now() WHERE id=?
                """, status, message == null ? null : message.substring(0, Math.min(500, message.length())), id);
    }

    public McpConnection discovered(
            UUID id, String toolsJson, String resourcesJson, String hash, boolean changed) {
        jdbc.update("""
                UPDATE agent_mcp_connection SET discovered_tools_json=?::jsonb,
                  discovered_resources_json=?::jsonb,schema_hash=?,
                  enabled=CASE WHEN ? THEN false ELSE enabled END,
                  last_health_status='HEALTHY',last_health_message=NULL,last_health_at=now(),
                  version=version+1,updated_at=now() WHERE id=?
                """, toolsJson, resourcesJson, hash, changed, id);
        return find(id).orElseThrow();
    }

    private RowMapper<McpConnection> connectionMapper() {
        return (rs, row) -> new McpConnection(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("code"), rs.getString("name"),
                McpTransport.valueOf(rs.getString("transport")), rs.getString("endpoint"),
                rs.getString("stdio_command_json"), McpAuthType.valueOf(rs.getString("auth_type")),
                rs.getString("credential_ciphertext"), integer(rs, "credential_key_version"),
                rs.getInt("timeout_ms"), rs.getInt("max_result_bytes"),
                rs.getString("tool_allowlist_json"), rs.getString("resource_allowlist_json"),
                rs.getString("discovered_tools_json"), rs.getString("discovered_resources_json"),
                rs.getString("schema_hash"), rs.getString("confirmed_schema_hash"),
                rs.getBoolean("enabled"), rs.getString("last_health_status"),
                rs.getString("last_health_message"), rs.getObject("last_health_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RowMapper<McpProviderAdmission> admissionMapper() {
        return (rs, row) -> new McpProviderAdmission(
                rs.getObject("connection_id", UUID.class),
                McpProviderType.valueOf(rs.getString("provider_type")),
                rs.getString("provider_owner"), rs.getString("data_scope"),
                rs.getString("retention_policy"), rs.getString("terms_url"),
                rs.getString("least_privilege_scope"), rs.getBoolean("read_only_endpoint"),
                McpAdmissionStatus.valueOf(rs.getString("status")),
                rs.getObject("reviewed_by", UUID.class),
                rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getObject("revoked_by", UUID.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
}
