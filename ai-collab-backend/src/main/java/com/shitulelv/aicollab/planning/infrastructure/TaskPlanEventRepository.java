package com.shitulelv.aicollab.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 7: Repository for plan audit events.
 * Records all mutations: generation, edit, repair, confirm, etc.
 */
@Repository
public class TaskPlanEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TaskPlanEventRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Append a new event.
     */
    public void append(TaskPlanEventRecord event) {
        jdbc.update("""
                INSERT INTO ai_task_plan_event
                (id, plan_id, from_version_id, to_version_id, actor_id, event_type,
                 changed_fields_json, before_hash, after_hash, issue_codes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
                """,
                event.id(), event.planId(), event.fromVersionId(), event.toVersionId(),
                event.actorId(), event.eventType(),
                writeJson(event.changedFields()),
                event.beforeHash(), event.afterHash(),
                writeJson(event.issueCodes()));
    }

    /**
     * Find events for a plan, ordered by creation time.
     */
    public List<TaskPlanEventRecord> findByPlan(UUID planId, int limit) {
        return jdbc.query("""
                SELECT * FROM ai_task_plan_event
                WHERE plan_id=?
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, n) -> new TaskPlanEventRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getObject("from_version_id", UUID.class),
                rs.getObject("to_version_id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("event_type"),
                parseJsonList(rs.getString("changed_fields_json")),
                rs.getString("before_hash"),
                rs.getString("after_hash"),
                parseJsonList(rs.getString("issue_codes_json")),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
        ), planId, limit);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.isBlank() || jsonStr.equals("[]")) return List.of();
            return json.readValue(jsonStr, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
