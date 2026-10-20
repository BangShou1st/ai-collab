package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一工具调用合同。
 * id 非空、name 非空、arguments 必须是 JSON Object。
 * thoughtSignature 可选：承载推理模型（如 Gemini 2.5 / 3.x）的工具调用签名元数据，
 * 供后续轮次回传模型保持内部推理状态；对不需要签名的 Provider / 模型为 null。
 */
public record ModelToolCall(
        String id,
        String name,
        JsonNode arguments,
        String thoughtSignature) {
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
        thoughtSignature = (thoughtSignature == null || thoughtSignature.isBlank()) ? null : thoughtSignature.strip();
    }

    public ModelToolCall(String id, String name, JsonNode arguments) {
        this(id, name, arguments, null);
    }
}

