package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 可信执行上下文。projectId、requesterId 和 role 来自受信任来源，模型参数不能覆盖。
 */
public record AgentExecutionContext(
        UUID runId,
        UUID sessionId,
        UUID projectId,
        UUID requesterId,
        String projectRole,
        boolean scheduled,
        AgentPageContext page,
        AgentRuntimeLimits limits,
        int depth,
        // V37 新增：可信提案上下文
        List<AgentProposalContext> proposals) {

    public AgentExecutionContext {
        if (runId == null) throw new IllegalArgumentException("runId 不能为 null");
        if (sessionId == null) throw new IllegalArgumentException("sessionId 不能为 null");
        if (projectId == null) throw new IllegalArgumentException("projectId 不能为 null");
        if (requesterId == null) throw new IllegalArgumentException("requesterId 不能为 null");
        if (projectRole == null) throw new IllegalArgumentException("projectRole 不能为 null");
        if (limits == null) throw new IllegalArgumentException("limits 不能为 null");
        if (proposals == null) throw new IllegalArgumentException("proposals 不能为 null");
    }

    public int maxSteps() { return limits.maxSteps(); }
    public int maxModelTurns() { return limits.maxModelTurns(); }
    public int maxToolCalls() { return limits.maxToolCalls(); }
    public int maxToolCallsPerTurn() { return limits.maxToolCallsPerTurn(); }
}
