package com.shitulelv.aicollab.agent.domain.model;

/**
 * agent_memory_revision.operation 修订操作类型(AC07-P1 第二波,对应设计 §5.3 的
 * REVISE / SUPERSEDE / DISABLE 三个生效动作;CREATE 不产生修订历史,NO_ACTION 不落库)。
 */
public enum AgentMemoryRevisionOperation {
    REVISE,
    SUPERSEDE,
    DISABLE
}
