package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Iterator;
import java.util.Set;

/**
 * 受控 JSON Schema 校验器，仅验证 Phase 1 需要的子集：
 * required、properties、type、enum、additionalProperties、
 * 字符串长度、数字边界、数组边界。
 * 不引入大型框架或 Spring AI。
 */
public final class ToolArgumentValidator {

    private ToolArgumentValidator() {}

    /**
     * 校验工具参数是否符合 Schema。
     * 返回 null 表示通过，否则返回错误描述。
     */
    public static String validate(JsonNode args, JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return null; // 无 Schema 不校验
        }
        if (args == null || !args.isObject()) {
            return "工具参数必须是 JSON Object";
        }
        return validateNode(args, schema, "");
    }

    private static String validateNode(JsonNode value, JsonNode schema, String path) {
        // type 校验（支持 string 和 array 两种形式）
        JsonNode typeNode = schema.path("type");
        if (!typeNode.isMissingNode() && !typeNode.isNull()) {
            String actualType = detectType(value);
            boolean typeMatch;
            if (typeNode.isArray()) {
                // ["string","null"] 形式：value 类型在允许列表中即通过
                typeMatch = false;
                for (JsonNode allowed : typeNode) {
                    if (actualType.equals(allowed.asText())) {
                        typeMatch = true;
                        break;
                    }
                }
            } else {
                typeMatch = actualType.equals(typeNode.asText());
            }
            if (!typeMatch) {
                return path + "类型期望 " + typeNode + "，实际为 " + actualType;
            }
        }

        // null 值跳过后续细化校验（type 数组已允许 null）
        if (value.isNull()) {
            return null;
        }

        // enum 校验
        JsonNode enumNode = schema.path("enum");
        if (enumNode.isArray() && !enumNode.isEmpty()) {
            boolean matched = false;
            for (JsonNode allowed : enumNode) {
                if (value.equals(allowed)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return path + "值不在允许的枚举范围内";
            }
        }

        // object 特有校验
        if (value.isObject()) {
            String objError = validateObject(value, schema, path);
            if (objError != null) return objError;
        }

        // string 特有校验
        if (value.isTextual()) {
            String strError = validateString(value, schema, path);
            if (strError != null) return strError;
        }

        // number 特有校验
        if (value.isNumber() || value.isInt() || value.isLong() || value.isDouble() || value.isFloat()) {
            String numError = validateNumber(value, schema, path);
            if (numError != null) return numError;
        }

        // array 特有校验
        if (value.isArray()) {
            String arrError = validateArray(value, schema, path);
            if (arrError != null) return arrError;
        }

        return null;
    }

    private static String validateObject(JsonNode value, JsonNode schema, String path) {
        // required 校验
        JsonNode requiredNode = schema.path("required");
        if (requiredNode.isArray()) {
            for (JsonNode req : requiredNode) {
                String field = req.asText();
                if (!value.has(field) || value.get(field).isNull()) {
                    return path + "缺少必填字段: " + field;
                }
            }
        }

        // properties 校验
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<String> fieldNames = value.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode fieldValue = value.get(fieldName);
                JsonNode fieldSchema = properties.path(fieldName);

                if (fieldSchema.isMissingNode() || fieldSchema.isNull()) {
                    // additionalProperties 校验
                    JsonNode additionalProps = schema.path("additionalProperties");
                    if (additionalProps.isBoolean() && !additionalProps.asBoolean()) {
                        return path + "不允许的额外字段: " + fieldName;
                    }
                    continue;
                }

                String fieldError = validateNode(fieldValue, fieldSchema, path + "." + fieldName);
                if (fieldError != null) return fieldError;
            }
        }

        return null;
    }

    private static String validateString(JsonNode value, JsonNode schema, String path) {
        String text = value.asText();

        // minLength
        JsonNode minLength = schema.path("minLength");
        if (minLength.isNumber() && text.length() < minLength.asInt()) {
            return path + "字符串长度不能少于 " + minLength.asInt();
        }

        // maxLength
        JsonNode maxLength = schema.path("maxLength");
        if (maxLength.isNumber() && text.length() > maxLength.asInt()) {
            return path + "字符串长度不能超过 " + maxLength.asInt();
        }

        return null;
    }

    private static String validateNumber(JsonNode value, JsonNode schema, String path) {
        double num = value.asDouble();

        // minimum
        JsonNode minimum = schema.path("minimum");
        if (minimum.isNumber() && num < minimum.asDouble()) {
            return path + "数值不能小于 " + minimum.asDouble();
        }

        // maximum
        JsonNode maximum = schema.path("maximum");
        if (maximum.isNumber() && num > maximum.asDouble()) {
            return path + "数值不能大于 " + maximum.asDouble();
        }

        return null;
    }

    private static String validateArray(JsonNode value, JsonNode schema, String path) {
        ArrayNode arr = (ArrayNode) value;

        // minItems
        JsonNode minItems = schema.path("minItems");
        if (minItems.isNumber() && arr.size() < minItems.asInt()) {
            return path + "数组元素不能少于 " + minItems.asInt() + " 个";
        }

        // maxItems
        JsonNode maxItems = schema.path("maxItems");
        if (maxItems.isNumber() && arr.size() > maxItems.asInt()) {
            return path + "数组元素不能超过 " + maxItems.asInt() + " 个";
        }

        // items 校验
        JsonNode itemsSchema = schema.path("items");
        if (itemsSchema.isObject()) {
            for (int i = 0; i < arr.size(); i++) {
                String itemError = validateNode(arr.get(i), itemsSchema, path + "[" + i + "]");
                if (itemError != null) return itemError;
            }
        }

        return null;
    }

    private static String detectType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isInt() || node.isLong()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isNull()) return "null";
        return "unknown";
    }
}
