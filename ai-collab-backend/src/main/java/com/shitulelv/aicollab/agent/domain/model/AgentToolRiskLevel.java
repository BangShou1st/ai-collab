package com.shitulelv.aicollab.agent.domain.model;

/**
 * 工具风险级别。
 */
public enum AgentToolRiskLevel {
    /** 只读工具，不修改业务数据 */
    READ_ONLY,
    /** 写工具，需要审批 */
    APPROVAL_REQUIRED,
    /** 禁止使用 */
    FORBIDDEN
}
