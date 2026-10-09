package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * agent_run 行映射的共享工厂。
 * 查询仓储与运行事件录制仓储各自执行 SQL，但必须使用同一套列映射，避免两处字段清单漂移。
 */
final class AgentRunMappers {

    private AgentRunMappers() {
    }

    static RowMapper<AgentRunView> runMapper() {
        return (ResultSet rs, int row) -> new AgentRunView(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class),
                rs.getString("role"),
                rs.getInt("depth"),
                rs.getString("goal"),
                AgentRunStatus.valueOf(rs.getString("status")),
                rs.getInt("max_steps"),
                rs.getInt("max_tool_calls"),
                rs.getInt("max_children"),
                // v2 的累计上限显式为 NULL：必须映射成 null，不能读成 0（0 会被当成"额度已耗尽"）
                nullableInt(rs, "max_input_tokens"),
                nullableInt(rs, "max_output_tokens"),
                rs.getInt("steps_used"),
                rs.getInt("tool_calls_used"),
                rs.getInt("children_used"),
                rs.getInt("input_tokens_used"),
                rs.getInt("output_tokens_used"),
                rs.getLong("input_tokens_actual"),
                rs.getLong("output_tokens_actual"),
                rs.getBoolean("token_usage_estimated"),
                rs.getBoolean("scheduled"),
                rs.getBoolean("correction_attempted"),
                rs.getInt("retry_count"),
                rs.getString("error_code"),
                rs.getString("plan_json"),
                rs.getString("page_context_json"),
                rs.getString("skill_code"),
                rs.getInt("version"),
                rs.getInt("context_policy_version"),
                "COMBINED".equals(rs.getString("budget_semantics")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** JDBC NULL → null（不是 0）：累计 token 上限的可空表达必须原样保留。 */
    private static Integer nullableInt(ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static RowMapper<ClaimedAgentRun> claimedMapper() {
        return (ResultSet rs, int row) -> new ClaimedAgentRun(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class),
                rs.getString("role"),
                rs.getInt("depth"),
                rs.getString("goal"),
                AgentRunStatus.valueOf(rs.getString("previous_status")),
                rs.getBoolean("scheduled"),
                rs.getBoolean("correction_attempted"),
                rs.getInt("version"));
    }
}
