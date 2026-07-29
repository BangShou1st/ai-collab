package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AgentToolArguments {
    private AgentToolArguments() {
    }

    static void requireFields(JsonNode arguments, Set<String> allowed) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Agent 工具参数必须是对象");
        }
        Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Agent 工具参数包含未知字段: " + field);
            }
        }
    }

    static String text(JsonNode arguments, String name, int maximum, boolean required) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException(name + " 不能为空");
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().codePointCount(0, value.textValue().length()) > maximum) {
            throw new IllegalArgumentException(name + " 必须是大小受限的文本");
        }
        return value.textValue().strip();
    }

    static int integer(JsonNode arguments, String name, int fallback, int min, int max) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        return value.intValue();
    }

    static UUID uuid(JsonNode arguments, String name, boolean required) {
        String value = text(arguments, name, 36, required);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 不是合法 UUID");
        }
    }

    static List<UUID> uuidList(JsonNode arguments, String name, int maximum) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException(name + " 必须是大小受限的 UUID 数组");
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalArgumentException(name + " 包含非法 UUID");
            try {
                UUID id = UUID.fromString(item.textValue());
                if (ids.contains(id)) throw new IllegalArgumentException(name + " 不能包含重复 ID");
                ids.add(id);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(name + " 包含非法或重复 UUID");
            }
        }
        return List.copyOf(ids);
    }
}
