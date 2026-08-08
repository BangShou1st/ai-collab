package com.shitulelv.aicollab.agent.domain.policy;

import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentStateMachine {
    private static final Map<AgentRunStatus, Set<AgentRunStatus>> TRANSITIONS = transitions();

    public void requireTransition(AgentRunStatus from, AgentRunStatus to) {
        if (from == null || to == null || !TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("非法 Agent 状态转换: " + from + " -> " + to);
        }
    }

    private static Map<AgentRunStatus, Set<AgentRunStatus>> transitions() {
        Map<AgentRunStatus, Set<AgentRunStatus>> values = new EnumMap<>(AgentRunStatus.class);
        values.put(AgentRunStatus.CREATED, EnumSet.of(
                AgentRunStatus.QUEUED, AgentRunStatus.CANCELED, AgentRunStatus.FAILED));
        values.put(AgentRunStatus.QUEUED, EnumSet.of(
                AgentRunStatus.RUNNING, AgentRunStatus.CANCELED, AgentRunStatus.FAILED,
                AgentRunStatus.BUDGET_EXCEEDED));
        values.put(AgentRunStatus.RUNNING, EnumSet.of(
                AgentRunStatus.QUEUED, AgentRunStatus.WAITING_FOR_APPROVAL,
                AgentRunStatus.WAITING_FOR_USER_INPUT,
                AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED_RETRYABLE,
                AgentRunStatus.FAILED, AgentRunStatus.CANCELED,
                AgentRunStatus.BUDGET_EXCEEDED));
        values.put(AgentRunStatus.WAITING_FOR_APPROVAL, EnumSet.of(
                AgentRunStatus.QUEUED, AgentRunStatus.FAILED,
                AgentRunStatus.CANCELED, AgentRunStatus.BUDGET_EXCEEDED));
        values.put(AgentRunStatus.WAITING_FOR_USER_INPUT, EnumSet.of(
                AgentRunStatus.QUEUED, AgentRunStatus.CANCELED, AgentRunStatus.FAILED));
        values.put(AgentRunStatus.FAILED_RETRYABLE, EnumSet.of(
                AgentRunStatus.QUEUED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED));
        return Map.copyOf(values);
    }
}
