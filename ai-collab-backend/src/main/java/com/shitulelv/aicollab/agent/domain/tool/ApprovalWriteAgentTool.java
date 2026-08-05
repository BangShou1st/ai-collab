package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ApprovalWriteAgentTool extends AgentTool {
    JsonNode normalize(AgentToolContext context, JsonNode arguments);

    JsonNode diff(AgentToolContext context, JsonNode normalizedArguments);

    /**
     * 执行前重新校验。
     * 检查目标实体是否仍然存在、版本是否匹配、业务前置条件是否成立。
     * 校验失败时抛出异常，不执行写操作。
     *
     * @param context 工具上下文
     * @param arguments 规范化后的参数
     */
    default void revalidate(AgentToolContext context, JsonNode arguments) {
        throw new IllegalStateException("审批写工具必须实现执行前重校验");
    }
}
