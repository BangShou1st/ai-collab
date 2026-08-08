package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 固定 Skill 定义。Skill 只决定工具白名单与输出要求，不能授予权限。
 */
public interface AgentSkill {

    /** Skill 唯一代码 */
    String code();

    /** 显示名称 */
    String displayName();

    /** 描述 */
    String description();

    /** 适用的页面路由 */
    Set<String> recommendedRoutes();

    /** 允许使用的工具白名单 */
    Set<String> allowedTools();

    /** 输入 Schema（JSON Schema） */
    JsonNode inputSchema();

    /** 是否允许写工具（需要审批） */
    boolean allowWriteTools();

    /** 是否允许使用外部 MCP 工具 */
    default boolean allowExternalTools() { return false; }

    /** 默认预算限制 */
    AgentRuntimeLimits defaultLimits();

    /** 系统指令片段 */
    String instruction();

    /** 输出要求描述 */
    String outputContract();
}
