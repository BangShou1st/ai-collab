package com.shitulelv.aicollab.common.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Jackson 2 trees used by internal model/parsing code to plain Java values.
 * Spring Boot 4 writes responses with Jackson 3, so exposing a Jackson 2 JsonNode directly
 * would serialize its bean metadata instead of the JSON value.
 */
public final class JsonApiValue {
    private JsonApiValue() {
    }

    public static Object from(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isObject()) {
            Map<String, Object> value = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> value.put(entry.getKey(), from(entry.getValue())));
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            node.forEach(item -> value.add(from(item)));
            return value;
        }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.bigIntegerValue();
        if (node.isFloatingPointNumber()) return new BigDecimal(node.asText());
        if (node.isTextual()) return node.textValue();
        if (node.isBinary()) {
            try {
                return node.binaryValue();
            } catch (java.io.IOException ignored) {
                return node.asText();
            }
        }
        return node.asText();
    }
}
