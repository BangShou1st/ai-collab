package com.shitulelv.aicollab.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 7: Repository for persisted validation issues.
 * Replaces flat error code list with structured, targeted issues.
 */
@Repository
public class TaskPlanIssueRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TaskPlanIssueRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Replace all issues for a specific version (atomic: delete old + insert new).
     */
    public void replaceForVersion(UUID planId, UUID versionId, List<StructuredValidationIssue> issues) {
        jdbc.update("DELETE FROM ai_task_plan_validation_issue WHERE plan_id=? AND version_id=?", planId, versionId);
        for (StructuredValidationIssue issue : issues) {
            jdbc.update("""
                    INSERT INTO ai_task_plan_validation_issue
                    (id, plan_id, version_id, code, severity, target_type, target_temp_key,
                     field_name, related_temp_key, safe_details_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    UUID.randomUUID(), planId, versionId,
                    issue.code(), issue.severity().name(),
                    issue.targetType(), issue.targetTempKey(),
                    issue.field(), issue.relatedTempKey(),
                    writeJson(issue.safeDetails()));
        }
    }

    /**
     * Find all issues for a version.
     */
    public List<StructuredValidationIssue> findByVersion(UUID planId, UUID versionId) {
        return jdbc.query("""
                SELECT code, severity, target_type, target_temp_key, field_name, related_temp_key, safe_details_json
                FROM ai_task_plan_validation_issue
                WHERE plan_id=? AND version_id=?
                ORDER BY created_at
                """, (rs, n) -> new StructuredValidationIssue(
                rs.getString("code"),
                ValidationIssueSeverity.valueOf(rs.getString("severity")),
                rs.getString("target_type"),
                rs.getString("target_temp_key"),
                rs.getString("field_name"),
                rs.getString("related_temp_key"),
                parseJson(rs.getString("safe_details_json"))
        ), planId, versionId);
    }

    /**
     * Count unresolved blocking issues for a plan's latest version.
     */
    public int countUnresolvedBlocking(UUID planId, UUID versionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_task_plan_validation_issue
                WHERE plan_id=? AND version_id=? AND resolved=false AND severity != 'WARNING'
                """, Integer.class, planId, versionId);
        return count != null ? count : 0;
    }

    /**
     * Mark specific issues as resolved.
     */
    public void markResolved(UUID planId, UUID versionId, List<UUID> issueIds, UUID actorId) {
        for (UUID issueId : issueIds) {
            jdbc.update("""
                    UPDATE ai_task_plan_validation_issue
                    SET resolved=true, resolved_by=?, resolved_at=now()
                    WHERE id=? AND plan_id=? AND version_id=?
                    """, actorId, issueId, planId, versionId);
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
            return this.json.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
