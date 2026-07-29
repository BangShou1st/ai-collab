package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;

import java.util.Iterator;
import java.util.Set;

public final class AgentDecisionParser {
    private static final int MAX_JSON_CODE_POINTS = 32_000;
    private static final int MAX_SHORT_TEXT = 2_000;
    private static final int MAX_ANSWER = 12_000;
    private final ObjectMapper json;

    public AgentDecisionParser(ObjectMapper json) {
        this.json = json;
    }

    public AgentDecision parse(String value, boolean correctionAttempted) {
        try {
            if (value == null || value.isBlank()
                    || value.codePointCount(0, value.length()) > MAX_JSON_CODE_POINTS) {
                throw new JsonProcessingException("empty or oversized") { };
            }
            JsonNode root = json.readTree(value);
            requireObject(root);
            String action = requiredText(root, "action", 40);
            return switch (action) {
                case "call_tool" -> parseCall(root);
                case "delegate" -> parseDelegate(root);
                case "final" -> parseFinal(root);
                default -> throw new IllegalArgumentException("未知 Agent action: " + action);
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    correctionAttempted
                            ? "Agent 决策不是合法 JSON，已不可纠正"
                            : "Agent 决策不是合法 JSON，可纠正一次",
                    exception);
        }
    }

    private AgentDecision parseCall(JsonNode root) {
        requireFields(root, Set.of("action", "tool", "arguments", "reason"));
        String tool = requiredText(root, "tool", 120);
        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments 必须是 JSON 对象");
        }
        if (arguments.has("projectId")) {
            throw new IllegalArgumentException("工具参数不能覆盖 projectId");
        }
        return new AgentDecision.CallTool(tool, arguments.deepCopy(),
                requiredText(root, "reason", MAX_SHORT_TEXT));
    }

    private AgentDecision parseDelegate(JsonNode root) {
        requireFields(root, Set.of("action", "role", "objective"));
        return new AgentDecision.Delegate(
                requiredText(root, "role", 80),
                requiredText(root, "objective", MAX_SHORT_TEXT));
    }

    private AgentDecision parseFinal(JsonNode root) {
        requireFields(root, Set.of("action", "answer", "citations", "inferences"));
        JsonNode citations = requiredArray(root, "citations");
        JsonNode inferences = requiredArray(root, "inferences");
        return new AgentDecision.FinalAnswer(
                requiredText(root, "answer", MAX_ANSWER),
                citations.deepCopy(),
                inferences.deepCopy());
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Agent 决策必须是 JSON 对象");
        }
    }

    private static void requireFields(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Agent 决策包含未知字段: " + name);
            }
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() > 100) {
            throw new IllegalArgumentException(field + " 必须是大小受限的数组");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().codePointCount(0, value.textValue().length()) > maximum) {
            throw new IllegalArgumentException(field + " 必须是大小受限的非空文本");
        }
        return value.textValue().strip();
    }
}
