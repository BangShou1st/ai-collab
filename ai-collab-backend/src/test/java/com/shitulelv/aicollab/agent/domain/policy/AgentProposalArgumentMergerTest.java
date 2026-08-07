package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentProposalArgumentMerger 行为测试。
 * 覆盖字段继承、覆盖、显式 null 清空、嵌套合并和非法输入。
 * 测试应因 AgentProposalArgumentMerger 尚未实现而失败。
 */
class AgentProposalArgumentMergerTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentProposalArgumentMerger merger;

    @BeforeEach
    void setUp() {
        merger = new AgentProposalArgumentMerger();
    }

    @Test
    void inheritsMissingFieldsFromCurrent() {
        JsonNode current = json.createObjectNode()
                .put("title", "原任务")
                .put("assignee", "张三")
                .put("priority", "HIGH");
        JsonNode patch = json.createObjectNode()
                .put("title", "新任务");

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("新任务");
        assertThat(result.get("assignee").asText()).isEqualTo("张三");
        assertThat(result.get("priority").asText()).isEqualTo("HIGH");
    }

    @Test
    void overridesExistingFieldsWithPatch() {
        JsonNode current = json.createObjectNode()
                .put("title", "原任务")
                .put("assignee", "张三");
        JsonNode patch = json.createObjectNode()
                .put("assignee", "李四");

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("原任务");
        assertThat(result.get("assignee").asText()).isEqualTo("李四");
    }

    @Test
    void explicitlyNullClearsField() {
        JsonNode current = json.createObjectNode()
                .put("title", "原任务")
                .put("dueDate", "2026-08-15");
        JsonNode patch = json.createObjectNode()
                .set("dueDate", json.nullNode());

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("原任务");
        assertThat(result.has("dueDate")).isTrue();
        assertThat(result.get("dueDate").isNull()).isTrue();
    }

    @Test
    void mergesNestedChangesRecursively() throws Exception {
        JsonNode current = json.readTree("""
                {
                    "title": "原任务",
                    "changes": {
                        "assignee": "张三",
                        "priority": "HIGH"
                    }
                }
                """);
        JsonNode patch = json.readTree("""
                {
                    "changes": {
                        "priority": "LOW"
                    }
                }
                """);

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("原任务");
        assertThat(result.get("changes").get("assignee").asText()).isEqualTo("张三");
        assertThat(result.get("changes").get("priority").asText()).isEqualTo("LOW");
    }

    @Test
    void rejectsNonObjectRootNode() {
        JsonNode current = json.createArrayNode().add("item");
        JsonNode patch = json.createObjectNode().put("title", "新任务");

        assertThatThrownBy(() -> merger.merge(current, patch))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode current2 = json.getNodeFactory().textNode("not an object");
        JsonNode patch2 = json.createObjectNode().put("title", "新任务");

        assertThatThrownBy(() -> merger.merge(current2, patch2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonObjectPatchNode() {
        JsonNode current = json.createObjectNode().put("title", "原任务");
        JsonNode patch = json.getNodeFactory().textNode("not an object");

        assertThatThrownBy(() -> merger.merge(current, patch))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotModifyInputNodes() {
        JsonNode current = json.createObjectNode()
                .put("title", "原任务")
                .put("assignee", "张三");
        JsonNode patch = json.createObjectNode()
                .put("title", "新任务");

        merger.merge(current, patch);

        assertThat(current.get("title").asText()).isEqualTo("原任务");
        assertThat(patch.get("title").asText()).isEqualTo("新任务");
    }

    @Test
    void handlesEmptyPatchGracefully() {
        JsonNode current = json.createObjectNode()
                .put("title", "原任务")
                .put("assignee", "张三");
        JsonNode patch = json.createObjectNode();

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("原任务");
        assertThat(result.get("assignee").asText()).isEqualTo("张三");
    }

    @Test
    void handlesEmptyCurrentWithPatch() {
        JsonNode current = json.createObjectNode();
        JsonNode patch = json.createObjectNode()
                .put("title", "新任务");

        JsonNode result = merger.merge(current, patch);

        assertThat(result.get("title").asText()).isEqualTo("新任务");
    }
}
