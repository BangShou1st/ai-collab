package com.shitulelv.aicollab.agent.domain.policy;

import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;

import java.util.Set;

public final class AgentToolPolicy {
    private static final Set<String> PERMANENTLY_FORBIDDEN = Set.of(
            "run_sql", "http_request", "delete_project", "delete_document",
            "delete_task", "delete_milestone", "manage_members", "configure_key",
            "confirm_ai_plan", "auto_approve");

    public void requireAllowed(AgentTool tool, AgentToolContext context) {
        if (tool == null || context == null || PERMANENTLY_FORBIDDEN.contains(tool.name())) {
            throw new IllegalArgumentException("Agent 工具被永久禁止");
        }
        if (tool.writesBusinessData()
                && (context.depth() != 0
                    || !"SUPERVISOR".equals(context.role()))) {
            throw new IllegalArgumentException("当前 Agent 运行不允许业务写工具");
        }
    }
}
