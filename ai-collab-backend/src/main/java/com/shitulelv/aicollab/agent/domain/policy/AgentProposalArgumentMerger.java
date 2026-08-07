package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Agent Proposal Argument Merger.
 * 按字段存在性覆盖 JSON，用于提案修订时的参数合并。
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
        if (current == null || !current.isObject()) {
            throw new IllegalArgumentException("current 参数必须是 object 类型");
        }
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("patch 参数必须是 object 类型");
        }
        return mergeObjects((ObjectNode) current.deepCopy(), (ObjectNode) patch);
    }

    /**
     * 递归合并两个 ObjectNode。
     * patch 中存在的字段覆盖 target，缺失字段继承 target。
     */
    private ObjectNode mergeObjects(ObjectNode target, ObjectNode patch) {
        patch.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode patchValue = entry.getValue();
            JsonNode currentValue = target.get(fieldName);

            if (patchValue.isNull()) {
                // 显式 null 清空字段
                target.set(fieldName, patchValue);
            } else if (patchValue.isObject() && currentValue != null && currentValue.isObject()) {
                // 双方都是 object 时递归合并
                mergeObjects((ObjectNode) currentValue, (ObjectNode) patchValue);
            } else {
                // patch 中的 scalar、array 或新字段覆盖 current
                target.set(fieldName, patchValue.deepCopy());
            }
        });
        return target;
    }
}
