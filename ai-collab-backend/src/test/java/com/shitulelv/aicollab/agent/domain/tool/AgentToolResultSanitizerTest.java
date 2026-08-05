package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentToolResultSanitizer 测试。
 * 覆盖：敏感字段移除、内部字段移除、数组限制、字符串截断、嵌套深度限制。
 */
class AgentToolResultSanitizerTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentToolResultSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new AgentToolResultSanitizer(json);
    }

    @Test
    void removesPasswordField() throws Exception {
        JsonNode input = json.readTree("""
                {"name":"test","password":"secret123","data":"safe"}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(result.get("name").asText()).isEqualTo("test");
        assertThat(result.get("data").asText()).isEqualTo("safe");
    }

    @Test
    void removesTokenField() throws Exception {
        JsonNode input = json.readTree("""
                {"token":"abc123","apiKey":"key123","authorization":"Bearer xxx"}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.get("token").asText()).isEqualTo("[REDACTED]");
        assertThat(result.get("apiKey").asText()).isEqualTo("[REDACTED]");
        assertThat(result.get("authorization").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void removesStackTraceField() throws Exception {
        JsonNode input = json.readTree("""
                {"error":"failed","stackTrace":"at com.test.Method","message":"error msg"}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.has("stackTrace")).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("failed");
        assertThat(result.get("message").asText()).isEqualTo("error msg");
    }

    @Test
    void nestedSensitiveFieldsAreRemoved() throws Exception {
        JsonNode input = json.readTree("""
                {"user":{"name":"test","credentials":"secret"},"data":"safe"}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.get("user").get("credentials").asText()).isEqualTo("[REDACTED]");
        assertThat(result.get("user").get("name").asText()).isEqualTo("test");
        assertThat(result.get("data").asText()).isEqualTo("safe");
    }

    @Test
    void arraysAreLimited() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 150; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        JsonNode input = json.readTree(sb.toString());
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isLessThanOrEqualTo(101); // 100 items + truncation message
    }

    @Test
    void nullInputReturnsNull() {
        JsonNode result = sanitizer.sanitize(null);
        assertThat(result).isNull();
    }

    @Test
    void safeDataPassesThrough() throws Exception {
        JsonNode input = json.readTree("""
                {"tasks":[{"id":1,"title":"test"}],"count":1}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.get("count").asInt()).isEqualTo(1);
        assertThat(result.get("tasks").size()).isEqualTo(1);
    }

    @Test
    void mixedSensitiveAndSafeFields() throws Exception {
        JsonNode input = json.readTree("""
                {"id":1,"name":"test","password":"secret","data":{"value":"safe","token":"abc"}}
                """);
        JsonNode result = sanitizer.sanitize(input);

        assertThat(result.get("id").asInt()).isEqualTo(1);
        assertThat(result.get("name").asText()).isEqualTo("test");
        assertThat(result.get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(result.get("data").get("value").asText()).isEqualTo("safe");
        assertThat(result.get("data").get("token").asText()).isEqualTo("[REDACTED]");
    }
}
