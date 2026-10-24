package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {
    String name();
    boolean writesBusinessData();
    AgentToolResult execute(AgentToolContext context, JsonNode arguments);

    default AgentToolExecutionSafety executionSafety() {
        return AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT;
    }

    /** Query an existing business operation only. Never dispatch or create an operation here. */
    default java.util.Optional<AgentToolResult> reconcile(AgentToolContext context, JsonNode arguments) {
        return java.util.Optional.empty();
    }

    default AgentToolDefinition definition() {
        return AgentToolDefinition.openObject(
                name(), "调用项目工具 " + name(), writesBusinessData());
    }
}
