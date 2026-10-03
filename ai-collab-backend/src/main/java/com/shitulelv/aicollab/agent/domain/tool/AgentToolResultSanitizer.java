package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Set;

/**
 * 工具结果统一清洗组件。
 * 为所有 Tool Result 实现统一处理：
 * 1. 最大字符数或最大字节数
 * 2. 最大数组项目数
 * 3. 最大嵌套深度
 * 4. 敏感字段清洗
 * 5. Token、API Key、密码、Authorization 等字段移除
 * 6. 内部异常类名移除
 * 7. 堆栈移除
 * 8. 截断时增加明确 truncated=true
 * 9. 不得破坏 JSON 结构
 * 10. 原始完整敏感结果不得写日志
 */
@Component
public class AgentToolResultSanitizer {

    private static final int MAX_RESULT_BYTES = 32 * 1024;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_NESTING_DEPTH = 10;
    private static final int MAX_STRING_LENGTH = 8000;

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwd", "secret", "token", "api_key", "apiKey",
            "apikey", "access_token", "accessToken", "authorization",
            "authorizationHeader", "credentials", "private_key", "privateKey",
            "encryption_key", "encryptionKey", "session_token", "sessionToken"
    );

    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "stackTrace", "stack_trace", "stacktrace", "cause", "trace",
            "className", "class_name", "exceptionClass", "exception_class"
    );

    private final ObjectMapper json;

    public AgentToolResultSanitizer(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 清洗工具结果。
     *
     * @param result 原始结果
     * @return 清洗后的结果，可能标记 truncated=true
     */
    public JsonNode sanitize(JsonNode result) {
        if (result == null || result.isNull()) {
            return result;
        }

        JsonNode sanitized = removeSensitiveFields(result, 0);
        sanitized = removeInternalFields(sanitized, 0);
        sanitized = limitArraySize(sanitized);
        sanitized = truncateStrings(sanitized);

        // 检查总大小
        try {
            byte[] bytes = json.writeValueAsBytes(sanitized);
            if (bytes.length > MAX_RESULT_BYTES) {
                // Reduce the JSON tree, never slice serialized UTF-8/JSON bytes.
                for(int stringLimit:new int[]{2000,1000,500,250,128}) {
                    sanitized=reduce(sanitized,stringLimit,MAX_ARRAY_ITEMS);
                    ObjectNode bounded=markBounded(sanitized,bytes.length);
                    if(json.writeValueAsBytes(bounded).length<=MAX_RESULT_BYTES) return bounded;
                }
                for(int arrayLimit:new int[]{20,10,5,2,1}) {
                    sanitized=reduce(sanitized,128,arrayLimit);
                    ObjectNode bounded=markBounded(sanitized,bytes.length);
                    if(json.writeValueAsBytes(bounded).length<=MAX_RESULT_BYTES) return bounded;
                }
                return json.createObjectNode().put("error","TOOL_RESULT_TOO_LARGE").put("truncated",true).put("originalSize",bytes.length);
            }
        } catch (Exception e) {
            // 序列化失败时返回安全的错误结果
            ObjectNode error = json.createObjectNode();
            error.put("error", "RESULT_SERIALIZATION_FAILED");
            error.put("truncated", true);
            return error;
        }

        return sanitized;
    }
    private ObjectNode markBounded(JsonNode node,int originalSize) {
        ObjectNode result=node.isObject()?((ObjectNode)node).deepCopy():json.createObjectNode().set("data",node);
        return result.put("truncated",true).put("originalSize",originalSize);
    }
    private JsonNode reduce(JsonNode node,int stringLimit,int arrayLimit) {
        if(node.isTextual() && node.asText().codePointCount(0,node.asText().length())>stringLimit)
            return json.getNodeFactory().textNode(node.asText().substring(0,node.asText().offsetByCodePoints(0,stringLimit))+"… [truncated]");
        if(node.isObject()){ObjectNode result=json.createObjectNode();node.fields().forEachRemaining(entry->result.set(entry.getKey(),reduce(entry.getValue(),stringLimit,arrayLimit)));return result;}
        if(node.isArray()){ArrayNode result=json.createArrayNode();for(int i=0;i<Math.min(node.size(),arrayLimit);i++)result.add(reduce(node.get(i),stringLimit,arrayLimit));return result;}
        return node;
    }

    /**
     * 清洗 AgentToolResult 的 data 字段。
     */
    public JsonNode sanitizeData(JsonNode data) {
        return sanitize(data);
    }

    private JsonNode removeSensitiveFields(JsonNode node, int depth) {
        if (depth > MAX_NESTING_DEPTH) return node;

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            ObjectNode result = json.createObjectNode();

            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode value = objectNode.get(fieldName);

                if (isSensitiveField(fieldName)) {
                    // 替换敏感字段为 [REDACTED]
                    result.put(fieldName, "[REDACTED]");
                } else if (value.isObject() || value.isArray()) {
                    result.set(fieldName, removeSensitiveFields(value, depth + 1));
                } else {
                    result.set(fieldName, value);
                }
            }
            return result;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode result = json.createArrayNode();
            int count = 0;
            for (JsonNode item : arrayNode) {
                if (count >= MAX_ARRAY_ITEMS) {
                    result.add("... [truncated, " + (arrayNode.size() - count) + " more items]");
                    break;
                }
                result.add(removeSensitiveFields(item, depth + 1));
                count++;
            }
            return result;
        }

        return node;
    }

    private JsonNode removeInternalFields(JsonNode node, int depth) {
        if (depth > MAX_NESTING_DEPTH) return node;

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            ObjectNode result = json.createObjectNode();

            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode value = objectNode.get(fieldName);

                if (isInternalField(fieldName)) {
                    // 跳过内部字段
                    continue;
                } else if (value.isObject() || value.isArray()) {
                    result.set(fieldName, removeInternalFields(value, depth + 1));
                } else {
                    result.set(fieldName, value);
                }
            }
            return result;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode result = json.createArrayNode();
            for (JsonNode item : arrayNode) {
                result.add(removeInternalFields(item, depth + 1));
            }
            return result;
        }

        return node;
    }

    private JsonNode limitArraySize(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            if (arrayNode.size() > MAX_ARRAY_ITEMS) {
                ArrayNode result = json.createArrayNode();
                for (int i = 0; i < MAX_ARRAY_ITEMS; i++) {
                    result.add(arrayNode.get(i));
                }
                result.add("... [truncated, " + (arrayNode.size() - MAX_ARRAY_ITEMS) + " more items]");
                return result;
            }
        }
        return node;
    }

    private JsonNode truncateStrings(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            if (text != null && text.length() > MAX_STRING_LENGTH) {
                return json.getNodeFactory().textNode(
                        text.substring(0, MAX_STRING_LENGTH) + "... [truncated]");
            }
        }
        return node;
    }

    private boolean isSensitiveField(String fieldName) {
        return SENSITIVE_FIELDS.contains(fieldName.toLowerCase().replace("-", "").replace("_", ""));
    }

    private boolean isInternalField(String fieldName) {
        return INTERNAL_FIELDS.contains(fieldName.toLowerCase().replace("-", "").replace("_", ""));
    }

}
