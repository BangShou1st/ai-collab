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
                rs.getInt("max_input_tokens"),
                rs.getInt("max_output_tokens"),
                rs.getInt("steps_used"),
                rs.getInt("tool_calls_used"),
                rs.getInt("children_used"),
                rs.getInt("input_tokens_used"),
                rs.getInt("output_tokens_used"),
                rs.getBoolean("token_usage_estimated"),
                rs.getBoolean("scheduled"),
                rs.getBoolean("correction_attempted"),
                rs.getInt("retry_count"),
                rs.getString("error_code"),
                rs.getString("plan_json"),
                rs.getString("page_context_json"),
                rs.getString("skill_code"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
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
