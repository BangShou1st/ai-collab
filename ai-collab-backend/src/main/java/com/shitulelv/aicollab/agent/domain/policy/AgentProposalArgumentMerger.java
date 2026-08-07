package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent Proposal Argument Merger.
 * 按字段存在性覆盖 JSON，用于提案修订时的参数合并。
 * TODO: Task 3 实现完整逻辑
 */
public final class AgentProposalArgumentMerger {

    /**
     * 合并当前参数和补丁参数。
     * patch 中存在的字段覆盖 current，缺失字段继承 current。
     *
     * @param current 当前完整参数
     * @param patch 补丁参数
     * @return 合并后的参数
     * @throws IllegalArgumentException 如果任一根节点不是 object
     */
    public JsonNode merge(JsonNode current, JsonNode patch) {
        // TODO: Task 3 实现完整逻辑
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
