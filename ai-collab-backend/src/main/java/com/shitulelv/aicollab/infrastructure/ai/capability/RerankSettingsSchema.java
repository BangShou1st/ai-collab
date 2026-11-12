package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 冻结 RERANK settings JSON Schema 的严格执行器。
 *
 * <p>schema 资源是唯一字段白名单来源：unknown property 一律拒绝（additionalProperties=false），
 * 缺省值来自 schema 的 {@code default}。整数不做浮点截断，字符串不 trim 后静默接受非法枚举。</p>
 */
@Component
public class RerankSettingsSchema {

    public static final String SCHEMA_RESOURCE = "/ai/capability/rerank-settings.schema.json";

    private final JsonNode schema;
    private final ObjectMapper objectMapper;

    public RerankSettingsSchema(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schema = loadSchema(objectMapper);
    }

    public RerankSettings validate(JsonNode raw) {
        JsonNode input = raw == null || raw.isNull() ? JsonNodeFactory.instance.objectNode() : raw;
        if (!input.isObject()) {
            throw invalid("settings 必须是 JSON 对象");
        }
        JsonNode properties = schema.path("properties");
        Set<String> allowed = fieldNames(properties);
        Iterator<String> names = input.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("settings 不允许字段: " + name);
            }
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        for (String field : allowed) {
            JsonNode definition = properties.path(field);
            JsonNode value = input.get(field);
            if (value == null) {
                value = definition.get("default");
            }
            if (value == null) {
                throw invalid("settings 缺少必填字段: " + field);
            }
            validateValue(field, value, definition);
            normalized.set(field, value);
        }

        RerankSettings settings = new RerankSettings(
                normalized.path("protocol").asText(),
                normalized.path("timeoutMs").asInt(),
                normalized.path("maxCandidates").asInt(),
                normalized.path("batchSize").asInt(),
                normalized.path("maxLength").asInt(),
                normalized.path("scoreType").asText(),
                normalized.path("topK").asInt(),
                normalized.path("healthPath").asText(),
                normalized.path("fallback").asText());

        if (settings.batchSize() > settings.maxCandidates()) {
            throw invalid("batchSize 不能大于 maxCandidates");
        }
        return settings;
    }

    public RerankSettings parse(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            throw invalid("settings_json 不能为空");
        }
        try {
            return validate(objectMapper.readTree(settingsJson));
        } catch (IOException exception) {
            throw invalid("settings_json 不是合法 JSON");
        }
    }

    private void validateValue(String field, JsonNode value, JsonNode definition) {
        String type = definition.path("type").asText();
        if ("integer".equals(type) && !value.isIntegralNumber()) {
            throw invalid(field + " 必须是整数");
        }
        if ("string".equals(type) && !value.isTextual()) {
            throw invalid(field + " 必须是字符串");
        }
        if (definition.has("enum") && !enumContains(definition.get("enum"), value)) {
            throw invalid(field + " 取值不在允许集合");
        }
        if (value.isIntegralNumber()) {
            long number = value.asLong();
            if (definition.has("minimum") && number < definition.get("minimum").asLong()) {
                throw invalid(field + " 小于最小值");
            }
            if (definition.has("maximum") && number > definition.get("maximum").asLong()) {
                throw invalid(field + " 大于最大值");
            }
        }
        if (value.isTextual()) {
            int length = value.asText().codePointCount(0, value.asText().length());
            if (definition.has("maxLength") && length > definition.get("maxLength").asInt()) {
                throw invalid(field + " 超过最大长度");
            }
            if (definition.has("pattern")
                    && !Pattern.compile(definition.get("pattern").asText()).matcher(value.asText()).matches()) {
                throw invalid(field + " 不匹配格式约束");
            }
        }
    }

    private static boolean enumContains(JsonNode enumNode, JsonNode value) {
        for (JsonNode allowed : enumNode) {
            if (allowed.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> fieldNames(JsonNode properties) {
        Set<String> names = new HashSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode loadSchema(ObjectMapper objectMapper) {
        try (InputStream stream = RerankSettingsSchema.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("缺少 RERANK settings schema: " + SCHEMA_RESOURCE);
            }
            JsonNode schema = objectMapper.readTree(stream);
            if (!schema.isObject() || !schema.path("properties").isObject()) {
                throw new IllegalStateException("RERANK settings schema 格式无效");
            }
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 RERANK settings schema", exception);
        }
    }

    private static RerankSettingsInvalidException invalid(String message) {
        return new RerankSettingsInvalidException(message);
    }

    public static final class RerankSettingsInvalidException extends IllegalArgumentException {
        public RerankSettingsInvalidException(String message) {
            super(message);
        }
    }
}
