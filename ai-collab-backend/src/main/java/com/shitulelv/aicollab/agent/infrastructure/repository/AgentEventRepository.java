package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class AgentEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentEventRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public AgentRunEventView append(
            UUID projectId, UUID runId, AgentEventType type, JsonNode payload) {
        List<Long> allocated = jdbc.queryForList("""
                UPDATE agent_run
                SET last_event_sequence=last_event_sequence+1,updated_at=now()
                WHERE project_id=? AND id=?
                RETURNING last_event_sequence
                """, Long.class, projectId, runId);
        if (allocated.isEmpty()) {
            throw new IllegalArgumentException("Agent 运行不存在");
        }
        long sequence = allocated.getFirst();
        JsonNode safePayload = payload == null ? json.createObjectNode() : payload.deepCopy();
        return jdbc.queryForObject("""
                INSERT INTO agent_run_event(project_id,run_id,sequence_no,type,payload_json)
                VALUES (?,?,?,?,?::jsonb)
                RETURNING id,project_id,run_id,sequence_no,type,payload_json::text,created_at
                """, (rs, row) -> new AgentRunEventView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("sequence_no"),
                        AgentEventType.valueOf(rs.getString("type")),
                        parse(rs.getString("payload_json")),
                        rs.getObject("created_at", OffsetDateTime.class)),
                projectId, runId, sequence, type.name(), safePayload.toString());
    }

    public List<AgentRunEventView> list(
            UUID projectId, UUID runId, long afterSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT id,project_id,run_id,sequence_no,type,payload_json::text,created_at
                FROM agent_run_event
                WHERE project_id=? AND run_id=? AND sequence_no>?
                ORDER BY sequence_no
                LIMIT ?
                """, (rs, row) -> new AgentRunEventView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("sequence_no"),
                        AgentEventType.valueOf(rs.getString("type")),
                        parse(rs.getString("payload_json")),
                        rs.getObject("created_at", OffsetDateTime.class)),
                projectId, runId, Math.max(0, afterSequence), safeLimit);
    }

    public boolean exists(UUID projectId, UUID runId, AgentEventType type) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_run_event
                WHERE project_id=? AND run_id=? AND type=?
                """, Integer.class, projectId, runId, type.name());
        return count != null && count > 0;
    }

    public long lastSequence(UUID projectId, UUID runId) {
        Long value = jdbc.queryForObject("""
                SELECT last_event_sequence FROM agent_run WHERE project_id=? AND id=?
                """, Long.class, projectId, runId);
        return value == null ? 0 : value;
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 事件 JSON 无法读取", exception);
        }
    }
}
