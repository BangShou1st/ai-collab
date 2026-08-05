package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 统一模型消息合同。所有 Provider 的多轮对话都使用此类型。
 * 不允许 Provider 专属 DTO 泄漏到公共合同。
 */
public sealed interface ModelMessage permits
        ModelMessage.System,
        ModelMessage.User,
        ModelMessage.Assistant,
        ModelMessage.ToolResult {

    record System(String content) implements ModelMessage {
        public System {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("system content 不能为空");
            }
            content = content.strip();
        }
    }

    record User(String content) implements ModelMessage {
        public User {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("user content 不能为空");
            }
            content = content.strip();
        }
    }

    record Assistant(String content, List<ModelToolCall> toolCalls) implements ModelMessage {
        public Assistant {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("assistant message 不能同时为空");
            }
        }
    }

    record ToolResult(
            String toolCallId,
            String toolName,
            JsonNode result,
            boolean error) implements ModelMessage {
        public ToolResult {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId 不能为空");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName 不能为空");
            }
            if (result == null) {
                throw new IllegalArgumentException("tool result 不能为空");
            }
        }
    }
}
