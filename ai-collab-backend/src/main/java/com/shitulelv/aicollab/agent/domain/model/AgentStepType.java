package com.shitulelv.aicollab.agent.domain.model;

public enum AgentStepType {
    MODEL_REQUEST,
    MODEL_DECISION,
    TOOL_CALL_PROPOSED,
    TOOL_CALL_COMPLETED,
    APPROVAL_REQUESTED,
    APPROVAL_RESOLVED,
    DELEGATION_REQUESTED,
    DELEGATION_COMPLETED,
    FINAL_ANSWER,
    ERROR
}
