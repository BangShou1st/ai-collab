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
    void numericTypesUseJsonSchemaValueSemanticsForSingleAndUnionTypes() throws Exception {
        for (String type : new String[]{"\"number\"", "[\"number\",\"null\"]"}) {
            var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"hours\":{\"type\":" + type + ",\"minimum\":0.5,\"maximum\":80}}}");
            for (String value : new String[]{"3", "3.5", "0.5", "80"})
                assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"hours\":" + value + "}"), schema)).as(type + " " + value).isNull();
            for (String value : new String[]{"\"3\"", "true", "{}", "0", "81"})
                assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"hours\":" + value + "}"), schema)).as(type + " " + value).isNotNull();
            assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"hours\":null}"), schema)).isEqualTo(type.startsWith("[") ? null : ".hours类型期望 \"number\"，实际为 null");
        }
        for (String type : new String[]{"\"integer\"", "[\"integer\",\"null\"]"}) {
            var schema = mapper.readTree("{\"properties\":{\"count\":{\"type\":" + type + "}}}");
            for (String value : new String[]{"3", "3.0", "3.000", "123456789012345678901234567890"})
                assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"count\":" + value + "}"), schema)).as(type + " " + value).isNull();
            assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"count\":3.5}"), schema)).isNotNull();
            if (type.startsWith("[")) assertThat(ToolArgumentValidator.validate(mapper.readTree("{\"count\":null}"), schema)).isNull();
        }
    }

    @Test
    void actualProposalSchemaAcceptsTheFailedIntegerPayloadIncludingNestedUnion() throws Exception {
        var tool = new com.shitulelv.aicollab.agent.infrastructure.tool.CreateTaskApprovalAgentTool(mapper,
                org.mockito.Mockito.mock(jakarta.validation.Validator.class),
                org.mockito.Mockito.mock(com.shitulelv.aicollab.work.application.service.TaskApplicationService.class),
                org.mockito.Mockito.mock(com.shitulelv.aicollab.project.application.service.ProjectApplicationService.class),
                org.mockito.Mockito.mock(com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository.class));
        var context = new AgentToolContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "OWNER", false, 0);
        var schema = new com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry(java.util.List.of(tool))
                .definitionsFor(context).getFirst().inputSchema();
        var payload = mapper.readTree("""
                {"title":"太空兔真实提案验收-180212-修订","status":"TODO","priority":"HIGH",
                 "approvalId":"e6f2da98-d45e-49de-8a88-82499a7da7ee",
                 "assigneeId":"9c4cd312-0e99-497a-8e3b-e78f98692df0",
                 "description":"用户指定：为本项目创建任务提案“太空兔真实提案验收-180212”，优先级 HIGH，预估 2 小时，负责人 Local Owner。仅生成待审批提案，不创建正式任务、不批准。",
                 "estimateHours":3}
                """);
        assertThat(ToolArgumentValidator.validate(payload, schema)).isNull();
        var nested = mapper.createObjectNode().put("type", "object");
        nested.putObject("properties").set("proposal", schema);
        assertThat(ToolArgumentValidator.validate(mapper.createObjectNode().set("proposal", payload), nested)).isNull();
        ((ObjectNode) payload).put("estimateHours", 81);
        assertThat(ToolArgumentValidator.validate(payload, schema)).contains(".estimateHours数值不能大于");
    }

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
    void trustedApprovalPatchMayOmitCreateRequiredFields() {
        ObjectNode schema = schemaWith(
                new String[]{"required", "properties"},
                mapper.createArrayNode().add("title"),
                props("title", "string"));
        schema.put("x-approval-patch", true);
        ((ObjectNode) schema.path("properties")).set(
                "approvalId", mapper.createObjectNode().put("type", "string"));
        ObjectNode patch = mapper.createObjectNode()
                .put("approvalId", "8aaad965-1fc8-42ff-9d9b-431c0fd51317")
                .put("title", (String) null);
        patch.remove("title");

        assertThat(ToolArgumentValidator.validate(patch, schema)).isNull();
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
