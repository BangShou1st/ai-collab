package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolArgumentValidator JSON Schema 校验测试。
 */
class ToolArgumentValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullSchemaPasses() {
        assertThat(ToolArgumentValidator.validate(mapper.createObjectNode(), null)).isNull();
    }

    @Test
    void nonObjectArgsFails() {
        JsonNode schema = mapper.createObjectNode().put("type", "object");
        assertThat(ToolArgumentValidator.validate(mapper.createArrayNode(), schema))
                .contains("JSON Object");
    }

    @Test
    void requiredFieldMissing() {
        ObjectNode schema = schemaWith(
                new String[]{"required", "properties"},
                mapper.createArrayNode().add("title"),
                props("title", "string"));
        assertThat(ToolArgumentValidator.validate(mapper.createObjectNode(), schema))
                .contains("必填字段");
    }

    @Test
    void requiredFieldPresentPasses() {
        ObjectNode schema = schemaWith(
                new String[]{"required", "properties"},
                mapper.createArrayNode().add("title"),
                props("title", "string"));
        ObjectNode args = mapper.createObjectNode().put("title", "hello");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void typeMismatchFails() {
        ObjectNode schema = schemaWith("properties", props("count", "integer"));
        ObjectNode args = mapper.createObjectNode().put("count", "not-a-number");
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("类型期望");
    }

    @Test
    void enumValidationFails() {
        ObjectNode statusProp = mapper.createObjectNode()
                .put("type", "string");
        statusProp.set("enum", mapper.createArrayNode().add("OPEN").add("CLOSED"));
        ObjectNode schema = schemaWith("properties", props("status", statusProp));
        ObjectNode args = mapper.createObjectNode().put("status", "INVALID");
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("枚举范围");
    }

    @Test
    void enumValidationPasses() {
        ObjectNode statusProp = mapper.createObjectNode()
                .put("type", "string");
        statusProp.set("enum", mapper.createArrayNode().add("OPEN").add("CLOSED"));
        ObjectNode schema = schemaWith("properties", props("status", statusProp));
        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void additionalPropertiesRejected() {
        ObjectNode schema = schemaWith(
                new String[]{"additionalProperties", "properties"},
                mapper.getNodeFactory().booleanNode(false), props("title", "string"));
        ObjectNode args = mapper.createObjectNode()
                .put("title", "hello")
                .put("extra", "not allowed");
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("额外字段");
    }

    @Test
    void additionalPropertiesAllowedByDefault() {
        ObjectNode schema = schemaWith("properties", props("title", "string"));
        ObjectNode args = mapper.createObjectNode()
                .put("title", "hello")
                .put("extra", "ok");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void stringMaxLengthExceeded() {
        ObjectNode nameProp = mapper.createObjectNode()
                .put("type", "string").put("maxLength", 5);
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode().put("name", "toolong");
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("长度不能超过");
    }

    @Test
    void stringMinLengthNotMet() {
        ObjectNode nameProp = mapper.createObjectNode()
                .put("type", "string").put("minLength", 3);
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode().put("name", "ab");
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("长度不能少于");
    }

    @Test
    void numberMaximumExceeded() {
        ObjectNode countProp = mapper.createObjectNode()
                .put("type", "integer").put("maximum", 10);
        ObjectNode schema = schemaWith("properties", props("count", countProp));
        ObjectNode args = mapper.createObjectNode().put("count", 11);
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("不能大于");
    }

    @Test
    void numberMinimumNotMet() {
        ObjectNode countProp = mapper.createObjectNode()
                .put("type", "integer").put("minimum", 0);
        ObjectNode schema = schemaWith("properties", props("count", countProp));
        ObjectNode args = mapper.createObjectNode().put("count", -1);
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("不能小于");
    }

    @Test
    void arrayMaxItemsExceeded() {
        ObjectNode itemsProp = mapper.createObjectNode()
                .put("type", "array").put("maxItems", 2);
        itemsProp.set("items", mapper.createObjectNode().put("type", "string"));
        ObjectNode schema = schemaWith("properties", props("items", itemsProp));
        ArrayNode arr = mapper.createArrayNode().add("a").add("b").add("c");
        ObjectNode args = mapper.createObjectNode();
        args.set("items", arr);
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("不能超过");
    }

    @Test
    void arrayItemValidationFails() {
        ObjectNode tagsProp = mapper.createObjectNode().put("type", "array");
        tagsProp.set("items", mapper.createObjectNode().put("type", "string"));
        ObjectNode schema = schemaWith("properties", props("tags", tagsProp));
        ArrayNode arr = mapper.createArrayNode().add(123);
        ObjectNode args = mapper.createObjectNode();
        args.set("tags", arr);
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("类型期望");
    }

    @Test
    void emptyArgsWithNoRequiredPasses() {
        ObjectNode schema = schemaWith("properties", props("title", "string"));
        assertThat(ToolArgumentValidator.validate(mapper.createObjectNode(), schema)).isNull();
    }

    @Test
    void nestedObjectValidation() {
        ObjectNode enabledProp = mapper.createObjectNode().put("type", "boolean");
        ObjectNode innerProps = mapper.createObjectNode();
        innerProps.set("enabled", enabledProp);
        ObjectNode configProp = mapper.createObjectNode().put("type", "object");
        configProp.set("required", mapper.createArrayNode().add("enabled"));
        configProp.set("properties", innerProps);

        ObjectNode schema = schemaWith("properties", props("config", configProp));
        ObjectNode args = mapper.createObjectNode();
        args.set("config", mapper.createObjectNode().put("enabled", true));
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void nestedObjectMissingRequiredField() {
        ObjectNode enabledProp = mapper.createObjectNode().put("type", "boolean");
        ObjectNode innerProps = mapper.createObjectNode();
        innerProps.set("enabled", enabledProp);
        ObjectNode configProp = mapper.createObjectNode().put("type", "object");
        configProp.set("required", mapper.createArrayNode().add("enabled"));
        configProp.set("properties", innerProps);

        ObjectNode schema = schemaWith("properties", props("config", configProp));
        ObjectNode args = mapper.createObjectNode();
        args.set("config", mapper.createObjectNode());
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("必填字段");
    }

    // ========== 数组 type 形式测试 ==========

    @Test
    void arrayTypeFormStringOrNullAcceptsString() {
        // "type":["string","null"] 应当接受字符串值
        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.set("type", mapper.createArrayNode().add("string").add("null"));
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode().put("name", "hello");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void arrayTypeFormStringOrNullAcceptsNull() {
        // "type":["string","null"] 应当接受 null 值
        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.set("type", mapper.createArrayNode().add("string").add("null"));
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode();
        args.putNull("name");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void arrayTypeFormRejectsMismatchedType() {
        // "type":["string","null"] 不应接受数字
        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.set("type", mapper.createArrayNode().add("string").add("null"));
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode().put("name", 123);
        assertThat(ToolArgumentValidator.validate(args, schema)).contains("类型期望");
    }

    @Test
    void arrayTypeFormNumberOrNullAcceptsNumber() {
        // "type":["number","null"] 应当接受数字
        ObjectNode countProp = mapper.createObjectNode();
        countProp.set("type", mapper.createArrayNode().add("number").add("null"));
        countProp.put("minimum", 0.5);
        ObjectNode schema = schemaWith("properties", props("count", countProp));
        ObjectNode args = mapper.createObjectNode().put("count", 10.5);
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    @Test
    void nullValueSkipsFieldValidation() {
        // null 值不应触发 minLength 等细化校验
        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.set("type", mapper.createArrayNode().add("string").add("null"));
        nameProp.put("minLength", 3);
        ObjectNode schema = schemaWith("properties", props("name", nameProp));
        ObjectNode args = mapper.createObjectNode();
        args.putNull("name");
        assertThat(ToolArgumentValidator.validate(args, schema)).isNull();
    }

    // ========== 辅助方法 ==========

    private ObjectNode schemaWith(String key, JsonNode value) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set(key, value);
        return schema;
    }

    private ObjectNode schemaWith(String[] keys, JsonNode v1, JsonNode v2) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set(keys[0], v1);
        schema.set(keys[1], v2);
        return schema;
    }

    private ObjectNode props(String name, String type) {
        ObjectNode obj = mapper.createObjectNode();
        obj.set(name, mapper.createObjectNode().put("type", type));
        return obj;
    }

    private ObjectNode props(String name, ObjectNode propSchema) {
        ObjectNode obj = mapper.createObjectNode();
        obj.set(name, propSchema);
        return obj;
    }
}
