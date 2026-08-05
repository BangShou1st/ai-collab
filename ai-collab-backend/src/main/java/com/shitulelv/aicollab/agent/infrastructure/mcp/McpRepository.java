package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class McpRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> CONFIGURATION = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public McpRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<McpConnection> list() {
        return jdbc.query("SELECT * FROM agent_mcp_connection ORDER BY code", connectionMapper());
    }

    public Optional<McpConnection> find(UUID id) {
        return jdbc.query("SELECT * FROM agent_mcp_connection WHERE id=?", connectionMapper(), id)
                .stream().findFirst();
    }

    public McpConnection create(McpConnection value) {
        return jdbc.queryForObject("""
                INSERT INTO agent_mcp_connection(
                  id,code,name,transport,endpoint,stdio_command_json,auth_type,
                  credential_ciphertext,credential_key_version,timeout_ms,max_result_bytes,
                  tool_allowlist_json,resource_allowlist_json,created_by)
                VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?::jsonb,?) RETURNING *
                """, connectionMapper(), value.id(), value.code(), value.name(),
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

    public List<McpBindingView> bindings(UUID projectId) {
        return jdbc.query("""
                SELECT b.*,c.code connection_code,c.name connection_name,c.enabled connection_enabled
                FROM agent_project_mcp_binding b JOIN agent_mcp_connection c ON c.id=b.connection_id
                WHERE b.project_id=? ORDER BY c.code
                """, bindingMapper(), projectId);
    }

    public Optional<McpBindingView> binding(UUID projectId, UUID connectionId) {
        return jdbc.query("""
                SELECT b.*,c.code connection_code,c.name connection_name,c.enabled connection_enabled
                FROM agent_project_mcp_binding b JOIN agent_mcp_connection c ON c.id=b.connection_id
                WHERE b.project_id=? AND b.connection_id=?
                """, bindingMapper(), projectId, connectionId).stream().findFirst();
    }

    public void upsertBinding(
            UUID projectId, UUID connectionId, UUID userId, boolean enabled,
            String tools, String resources, String configuration, int version) {
        int changed = jdbc.update("""
                INSERT INTO agent_project_mcp_binding(project_id,connection_id,created_by,enabled,
                  allowed_tools_json,allowed_resources_json,configuration_json)
                VALUES (?,?,?, ?,?::jsonb,?::jsonb,?::jsonb)
                ON CONFLICT(project_id,connection_id) DO UPDATE SET enabled=excluded.enabled,
                  allowed_tools_json=excluded.allowed_tools_json,
                  allowed_resources_json=excluded.allowed_resources_json,
                  configuration_json=excluded.configuration_json,
                  version=agent_project_mcp_binding.version+1,updated_at=now()
                WHERE agent_project_mcp_binding.version=?
                """, projectId, connectionId, userId, enabled, tools, resources, configuration, version);
        if (changed != 1) throw new com.shitulelv.aicollab.common.exception.BusinessException(
                com.shitulelv.aicollab.common.exception.ErrorCode.VERSION_CONFLICT);
    }

    public boolean deleteBinding(UUID projectId, UUID connectionId) {
        return jdbc.update("DELETE FROM agent_project_mcp_binding WHERE project_id=? AND connection_id=?",
                projectId, connectionId) == 1;
    }

    private RowMapper<McpConnection> connectionMapper() {
        return (rs, row) -> new McpConnection(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
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

    private RowMapper<McpBindingView> bindingMapper() {
        return (rs, row) -> new McpBindingView(
                rs.getObject("project_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("connection_code"), rs.getString("connection_name"),
                rs.getBoolean("connection_enabled"), rs.getBoolean("enabled"),
                strings(rs.getString("allowed_tools_json")), strings(rs.getString("allowed_resources_json")),
                tree(rs.getString("configuration_json")), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private List<String> strings(String value) {
        try { return json.readValue(value, STRINGS); }
        catch (Exception exception) { throw new IllegalStateException("无效 MCP 白名单", exception); }
    }

    private Map<String, Object> tree(String value) {
        try { return json.readValue(value, CONFIGURATION); }
        catch (Exception exception) { throw new IllegalStateException("无效 MCP 配置", exception); }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
}
