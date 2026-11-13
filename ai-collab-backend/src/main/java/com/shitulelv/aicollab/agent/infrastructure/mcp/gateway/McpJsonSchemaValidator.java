package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Gateway 侧最小 JSON Schema 校验器。只使用本地已确认 Schema，不加载远程引用。
 */
final class McpJsonSchemaValidator {

    private McpJsonSchemaValidator() {
    }

    static boolean valid(JsonNode schema, JsonNode value) {
        try {
            return matches(schema, value, schema, 0);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean matches(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        if (schema == null || !schema.isObject() || value == null) {
            return false;
        }
        if (schema.has("$ref")) {
            JsonNode referenced = resolve(root, schema.path("$ref").asText(""), depth + 1);
            return referenced != null && matches(referenced, value, root, depth + 1);
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            return false;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null) {
            if (!enumValues.isArray() || !contains(enumValues, value)) {
                return false;
            }
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            return false;
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && (!allOf.isArray() || !allMatch(allOf, value, root, depth))) {
            return false;
        }
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && (!anyOf.isArray() || !anyMatch(anyOf, value, root, depth))) {
            return false;
        }
        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null && (!oneOf.isArray() || countMatches(oneOf, value, root, depth) != 1)) {
            return false;
        }
        JsonNode not = schema.get("not");
        if (not != null && matches(not, value, root, depth)) {
            return false;
        }
        if (value.isObject()) {
            return objectMatches(schema, value, root, depth);
        }
        if (value.isArray()) {
            return arrayMatches(schema, value, root, depth);
        }
        if (value.isTextual()) {
            return stringMatches(schema, value.textValue());
        }
        if (value.isNumber()) {
            return numberMatches(schema, value.decimalValue());
        }
        return true;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode item : type) {
                if (typeMatches(item, value)) {
                    return true;
                }
            }
            return false;
        }
        if (!type.isTextual()) {
            return false;
        }
        return switch (type.textValue()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static boolean objectMatches(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                return false;
            }
            for (JsonNode name : required) {
                if (!name.isTextual() || !value.has(name.textValue())) {
                    return false;
                }
            }
        }
        JsonNode properties = schema.get("properties");
        Set<String> declared = new HashSet<>();
        if (properties != null) {
            if (!properties.isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> property = fields.next();
                declared.add(property.getKey());
                if (value.has(property.getKey())
                        && !matches(property.getValue(), value.get(property.getKey()), root, depth)) {
                    return false;
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                if (!declared.contains(names.next())) {
                    return false;
                }
            }
        }
        if (additional != null && additional.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!declared.contains(field.getKey())
                        && !matches(additional, field.getValue(), root, depth)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean arrayMatches(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        int minItems = schema.path("minItems").asInt(-1);
        if (minItems >= 0 && value.size() < minItems) {
            return false;
        }
        int maxItems = schema.path("maxItems").asInt(-1);
        if (maxItems >= 0 && value.size() > maxItems) {
            return false;
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            for (JsonNode item : value) {
                if (!matches(items, item, root, depth)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean stringMatches(JsonNode schema, String value) {
        int length = value.codePointCount(0, value.length());
        int minLength = schema.path("minLength").asInt(-1);
        if (minLength >= 0 && length < minLength) {
            return false;
        }
        int maxLength = schema.path("maxLength").asInt(-1);
        if (maxLength >= 0 && length > maxLength) {
            return false;
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            if (!pattern.isTextual()) {
                return false;
            }
            try {
                return Pattern.compile(pattern.textValue()).matcher(value).find();
            } catch (PatternSyntaxException invalid) {
                return false;
            }
        }
        return true;
    }

    private static boolean numberMatches(JsonNode schema, BigDecimal value) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && (!minimum.isNumber() || value.compareTo(minimum.decimalValue()) < 0)) {
            return false;
        }
        JsonNode maximum = schema.get("maximum");
        return maximum == null
                || (maximum.isNumber() && value.compareTo(maximum.decimalValue()) <= 0);
    }

    private static boolean contains(JsonNode values, JsonNode target) {
        for (JsonNode value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allMatch(JsonNode schemas, JsonNode value, JsonNode root, int depth) {
        for (JsonNode schema : schemas) {
            if (!matches(schema, value, root, depth)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMatch(JsonNode schemas, JsonNode value, JsonNode root, int depth) {
        for (JsonNode schema : schemas) {
            if (matches(schema, value, root, depth)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatches(JsonNode schemas, JsonNode value, JsonNode root, int depth) {
        int matches = 0;
        for (JsonNode schema : schemas) {
            if (matches(schema, value, root, depth)) {
                matches++;
            }
        }
        return matches;
    }

    private static JsonNode resolve(JsonNode root, String reference, int depth) {
        if (depth > 16 || reference == null) {
            return null;
        }
        if ("#".equals(reference)) {
            return root;
        }
        if (!reference.startsWith("#/")) {
            return null;
        }
        JsonNode current = root;
        for (String rawSegment : reference.substring(2).split("/", -1)) {
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                try {
                    int index = Integer.parseInt(segment);
                    current = index >= 0 && index < current.size() ? current.get(index) : null;
                } catch (NumberFormatException invalid) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
