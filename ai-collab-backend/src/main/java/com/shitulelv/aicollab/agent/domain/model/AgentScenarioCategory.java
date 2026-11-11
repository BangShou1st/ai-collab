package com.shitulelv.aicollab.agent.domain.model;

/**
 * Scenario 类别。
 */
public enum AgentScenarioCategory {

    /** 固定只读/研究场景，可随页面路由兜底选择。 */
    SCENARIO,

    /** 写工作流场景，必须由用户显式选择才能进入。 */
    WORKFLOW
}
