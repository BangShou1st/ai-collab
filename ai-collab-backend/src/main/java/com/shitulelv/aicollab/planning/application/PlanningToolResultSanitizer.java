package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;

/**
 * planning 工具结果统一清洗器（纯函数式组件，无 Spring 依赖）。
 *
 * <p><b>来源说明</b>：本类是 {@code agent.domain.tool.AgentToolResultSanitizer} 的
 * planning 包内等价实现（核心保证一致：敏感字段脱敏、内部字段移除、数组/字符串截断、
 * 有界字节上限、截断标记、不破坏 JSON 结构）。不直接 import 的原因：agent 模块已依赖
 * planning 模块，引入 planning→agent 依赖会形成双向模块依赖，不符合现有分层惯例。</p>
 *
 * <p>planning 场景的参数更紧：单条结果 64kB、字符串 4000 字符、数组 50 项——
 * 工具结果要整体注入下一轮 prompt（受 {@code MAX_PROMPT_CODEPOINTS} 约束），
 * 比 agent 会话正文预算保守。</p>
 */
public final class PlanningToolResultSanitizer {

    private static final int MAX_RESULT_BYTES = 64 * 1024;
    private static final int MAX_ARRAY_ITEMS = 50;
    private static final int MAX_STRING_LENGTH = 4_000;
    private static final int MAX_NESTING_DEPTH = 8;

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwd", "secret", "token", "api_key", "apiKey", "apikey",
            "access_token", "accessToken", "authorization", "credentials",
            "private_key", "privateKey", "encryption_key", "encryptionKey");

    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "stackTrace", "stack_trace", "stacktrace", "cause", "trace",
            "className", "class_name", "exceptionClass", "exception_class");

    private final ObjectMapper json;

    public PlanningToolResultSanitizer(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 清洗工具结果：敏感字段脱敏 → 内部字段移除 → 字符串截断 → 数组截断 → 总量兜底。
     * 超过字节上限时逐级压缩并标记 {@code truncated=true}，永不破坏 JSON 结构。
     */
    public JsonNode sanitize(JsonNode result) {
        if (result == null || result.isNull()) return result;
        JsonNode sanitized = filterFields(result, 0);
        try {
            byte[] bytes = json.writeValueAsBytes(sanitized);
            if (bytes.length <= MAX_RESULT_BYTES) return sanitized;
            for (int stringLimit : new int[]{2000, 1000, 500, 250, 128}) {
                JsonNode reduced = reduce(sanitized, stringLimit, MAX_ARRAY_ITEMS);
                ObjectNode bounded = markBounded(reduced, bytes.length);
                if (json.writeValueAsBytes(bounded).length <= MAX_RESULT_BYTES) return bounded;
            }
            for (int arrayLimit : new int[]{20, 10, 5, 2, 1}) {
                JsonNode reduced = reduce(sanitized, 128, arrayLimit);
                ObjectNode bounded = markBounded(reduced, bytes.length);
                if (json.writeValueAsBytes(bounded).length <= MAX_RESULT_BYTES) return bounded;
            }
            return json.createObjectNode()
                    .put("error", "TOOL_RESULT_TOO_LARGE")
                    .put("truncated", true)
                    .put("originalSize", bytes.length);
        } catch (Exception serializationFailure) {
            // 序列化失败时返回安全的错误结果，不向调用方泄露异常细节
            return json.createObjectNode()
                    .put("error", "RESULT_SERIALIZATION_FAILED")
                    .put("truncated", true);
        }
    }

    private ObjectNode markBounded(JsonNode node, int originalSize) {
        ObjectNode copy = node.isObject() ? node.deepCopy() : json.createObjectNode().set("data", node);
        return copy.put("truncated", true).put("originalSize", originalSize);
    }

    private JsonNode reduce(JsonNode node, int stringLimit, int arrayLimit) {
        if (node.isTextual() && node.asText().length() > stringLimit) {
            return json.getNodeFactory().textNode(node.asText().substring(0, stringLimit) + "… [truncated]");
        }
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), reduce(entry.getValue(), stringLimit, arrayLimit)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (int i = 0; i < Math.min(node.size(), arrayLimit); i++) {
                result.add(reduce(node.get(i), stringLimit, arrayLimit));
            }
            return result;
        }
        return node;
    }

    /** 递归移除敏感字段（脱敏为 [REDACTED]）与内部字段（直接删除），并截断超长字符串、超长数组。 */
    private JsonNode filterFields(JsonNode node, int depth) {
        if (depth > MAX_NESTING_DEPTH) return node;
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode value = node.get(field);
                String normalized = field.toLowerCase().replace("-", "").replace("_", "");
                if (INTERNAL_FIELDS.contains(normalized)) continue;
                if (SENSITIVE_FIELDS.contains(normalized)) {
                    result.put(field, "[REDACTED]");
                } else if (value.isObject() || value.isArray()) {
                    result.set(field, filterFields(value, depth + 1));
                } else if (value.isTextual() && value.textValue() != null
                        && value.textValue().length() > MAX_STRING_LENGTH) {
                    result.put(field, value.textValue().substring(0, MAX_STRING_LENGTH) + "… [truncated]");
                } else {
                    result.set(field, value);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count >= MAX_ARRAY_ITEMS) {
                    result.add("… [truncated, " + (node.size() - count) + " more items]");
                    break;
                }
                result.add(filterFields(item, depth + 1));
                count++;
            }
            return result;
        }
        if (node.isTextual() && node.textValue() != null && node.textValue().length() > MAX_STRING_LENGTH) {
            return json.getNodeFactory().textNode(node.textValue().substring(0, MAX_STRING_LENGTH) + "… [truncated]");
        }
        return node;
    }
}
