package com.shitulelv.aicollab.agent.application.view;

import java.util.List;

public record AgentRunDetailView(
        AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
        List<AgentStepView> steps, long lastEventSequence, java.util.UUID pendingApprovalId,
        com.fasterxml.jackson.databind.JsonNode modelConfiguration, java.util.Map<String,Integer> recoveryCounters,
        java.time.OffsetDateTime pauseRequestedAt, java.math.BigDecimal totalCost) {
    /** 兼容入口：不聚合成本（totalCost 为 null，表示无成本数据）。 */
    public AgentRunDetailView(AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
            List<AgentStepView> steps,long lastEventSequence,java.util.UUID pendingApprovalId) {
        this(run,plan,steps,lastEventSequence,pendingApprovalId,null,java.util.Map.of(),null,null);
    }
    /** 兼容入口：沿用诊断字段缺省（modelConfiguration/recoveryCounters/pauseRequestedAt）与成本缺省。 */
    public AgentRunDetailView(AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
            List<AgentStepView> steps, long lastEventSequence, java.util.UUID pendingApprovalId,
            com.fasterxml.jackson.databind.JsonNode modelConfiguration, java.util.Map<String,Integer> recoveryCounters,
            java.time.OffsetDateTime pauseRequestedAt) {
        this(run, plan, steps, lastEventSequence, pendingApprovalId, modelConfiguration,
                recoveryCounters, pauseRequestedAt, null);
    }
    public AgentRunDetailView(AgentRunView run, List<AgentStepView> steps) {
        this(run, null, steps, 0, null);
    }
}
