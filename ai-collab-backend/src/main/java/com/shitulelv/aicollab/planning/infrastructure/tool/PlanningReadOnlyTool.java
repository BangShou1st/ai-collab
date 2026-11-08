package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * planning 专属只读工具的统一接口（设计文档 §5.2「给 planner 配工具」）。
 *
 * <p>与 agent 模块的 {@code AgentTool} 是两套体系：planner 不进 agent run 队列、
 * 无租约、无审批流——这里只有纯只读工具，由编排器内的有界小循环
 * （{@code PlanningToolLoop}）驱动。实现方约定：</p>
 * <ul>
 *   <li>只读：不得写任何业务数据；</li>
 *   <li>权限：入口处用 {@code ProjectAccessGuard.requireMember} 校验（纵深防御，
 *       底层服务自身也有权限校验）；</li>
 *   <li>失败不抛出到循环外：可预见的业务失败（参数非法、检索未命中）应返回
 *       带 {@code error} 字段的 JSON，由模型决定下一步；</li>
 *   <li>输出在进入 prompt 与持久化前都会经 {@code PlanningToolResultSanitizer} 清洗。</li>
 * </ul>
 */
public interface PlanningReadOnlyTool {

    /** 工具名（模型可见的调用键，也是 allowedTools 白名单的键）。 */
    String name();

    /** 给模型看的功能描述（会进入 prompt 的工具说明段）。 */
    String description();

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数（未经校验的不可信输入，实现方自行裁剪/校验）
     * @param projectId 当前规划项目
     * @param userId    触发生成的用户（权限校验主体）
     * @return 结构化结果 JSON
     */
    JsonNode execute(JsonNode arguments, UUID projectId, UUID userId);
}
