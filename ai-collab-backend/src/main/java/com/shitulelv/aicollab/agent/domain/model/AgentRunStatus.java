package com.shitulelv.aicollab.agent.domain.model;

public enum AgentRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_USER_INPUT,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED,
    CANCELED,
    BUDGET_EXCEEDED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == BUDGET_EXCEEDED;
    }
}
