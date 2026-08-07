package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.policy.AgentProposalArgumentMerger;

public interface ApprovalWriteAgentTool extends AgentTool {
    JsonNode normalize(AgentToolContext context, JsonNode arguments);

    JsonNode diff(AgentToolContext context, JsonNode normalizedArguments);

    /**
     * 返回工具的提案族标识。
     * 用于提案匹配、修订和历史记录。
     */
    default AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.TASK_CREATE; // 默认值，具体工具应覆盖
    }

    /**
     * 合并当前参数和补丁参数。
     * 默认委托给 AgentProposalArgumentMerger 进行字段存在性覆盖。
     *
     * @param current 当前完整参数
     * @param patch 补丁参数
     * @return 合并后的参数
     */
    default JsonNode mergeArguments(JsonNode current, JsonNode patch) {
        return new AgentProposalArgumentMerger().merge(current, patch);
    }

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
