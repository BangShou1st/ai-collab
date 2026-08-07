package com.shitulelv.aicollab.agent.domain.model;

/**
 * 提案族枚举，定义五类现有写操作的稳定标识。
 * 用于提案匹配、修订和历史记录。
 */
public enum AgentProposalFamily {
    TASK_CREATE,
    TASK_UPDATE,
    MILESTONE_CREATE,
    MILESTONE_UPDATE,
    MEMORY_CREATE
}
