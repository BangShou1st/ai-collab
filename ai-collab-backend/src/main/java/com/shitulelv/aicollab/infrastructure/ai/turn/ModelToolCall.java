package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一工具调用合同。
 * id 非空、name 非空、arguments 必须是 JSON Object。
 */
public record ModelToolCall(
        String id,
        String name,
        JsonNode arguments) {
    public ModelToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("toolCall id 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("toolCall name 不能为空");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("toolCall arguments 必须是 JSON object");
        }
        name = name.strip();
        arguments = arguments.deepCopy();
    }
}
