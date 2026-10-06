package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentToolOutputProjector（从组装器抽出的确定性投影组件）：
 * 列表页、规划输出、文档正文与通用 JSON 结果的模型可见视图。
 * 纯投影测试；组装/协议配对回归见 AgentModelMessageComposerV2Test，
 * "工具分页 → 清洗 → 模型视图 → 续页" 串联回归见 AgentListPaginationChainTest。
 */
class AgentToolOutputProjectorTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentToolOutputProjector projector = new AgentToolOutputProjector(json);

    @Test
    void planningProjectionRetainsNestedVersionAndTaskIdentitiesForScopedRepair() {
        var result = json.createObjectNode();
        var data = result.putObject("data");
        data.put("baseVersionId", "11111111-1111-1111-1111-111111111111");
        data.put("expectedVersionNo", 2);
        data.putObject("version").put("id", "11111111-1111-1111-1111-111111111111");
        var draft = data.putObject("draft");
        draft.putArray("tasks").addObject().put("tempKey", "t1").put("description", "长描述".repeat(3000));
        var projected = projector.projectToolOutput(result, 6000);
        assertThat(projected.path("data").path("baseVersionId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(projected.path("data").path("draft").path("tasks").get(0).path("tempKey").asText()).isEqualTo("t1");
        assertThat(projected.path("projection").asText()).isEqualTo("DETERMINISTIC");
        assertThat(projected.toString().length()).isLessThanOrEqualTo(6000);
        assertThat(projector.projectToolOutput(result, 1500).path("data").path("draft").path("tasks").get(0).path("tempKey").asText()).isEqualTo("t1");
    }

    @Test
    void projectedDocumentCannotRetainFullReadClaim() {
        var output = json.createObjectNode();
        var data = output.putObject("data");
        data.put("coverage", "FULL");
        data.put("fullDocumentRead", true);
        data.put("content", "正文".repeat(6000));
        var projected = projector.projectToolOutput(output, 1500);
        assertThat(projected.path("fullDocumentRead").asBoolean()).isFalse();
        assertThat(projected.path("evidenceScope").asText()).isEqualTo("PROJECTED_PARTIAL_OBSERVATION");
    }

    @Test
    void bodyItemProjectionKeepsVisibleRangeConsistentWithContent() {
        // 回归根因：正文截到 200 字后，原始范围终点/截断标记仍按原值保留，
        // 模型把序列化 JSON 长度（originalChars）误当正文长度
        var output = json.createObjectNode();
        var data = output.putObject("data");
        var item = data.putObject("item");
        item.put("content", "章节正文".repeat(400)); // 1600 字，超过 cap 触发投影
        item.put("fromOffset", 4956);
        item.put("throughOffset", 5513);
        item.put("chunkNo", 3);
        item.put("heading", "4.4 摘要与压缩");
        var projected = projector.projectToolOutput(output, 1500);

        var view = projected.path("data").path("item");
        String visibleContent = view.path("content").asText();
        // 可见范围与实际字符串一致：可见终点 = 起点 + 实际可见正文长度
        assertThat(view.path("fromOffset").asInt()).isEqualTo(4956);
        assertThat(view.path("throughOffset").asInt()).isEqualTo(4956 + 200);
        assertThat(view.path("originalThroughOffset").asInt()).isEqualTo(5513);
        assertThat(view.path("omittedChars").asInt()).isEqualTo(5513 - 4956 - 200);
        assertThat(view.path("bodyProjection").asText()).isEqualTo("MODEL_VISIBLE_ONLY");
        assertThat(visibleContent.startsWith("章节正文")).isTrue();
        assertThat(visibleContent.length()).isEqualTo(200 + "… [projected]".length());
        // 序列化大小与正文长度的语义区分明确
        assertThat(projected.path("originalCharsSemantics").asText())
                .isEqualTo("SERIALIZED_TOOL_RESULT_JSON_CHARS_NOT_BODY_LENGTH");
        assertThat(projected.path("modelVisibleChars").asInt()).isEqualTo(projected.toString().length());
        // 续读提示：从可见终点续读，不跳过模型未见内容
        assertThat(projected.path("resumeHint").asText()).isNotBlank();
    }

    @Test
    void repeatedProjectionDoesNotDistortRangeInfo() {
        var output = json.createObjectNode();
        var data = output.putObject("data");
        var item = data.putObject("item");
        item.put("content", "正文内容".repeat(400)); // 1600 字，超过 cap 触发投影
        item.put("fromOffset", 100);
        item.put("throughOffset", 2200);

        JsonNode once = projector.projectToolOutput(output, 1500);
        // 模拟重复压缩：对已投影视图再次投影（如降级重组后再次组装）
        JsonNode twice = projector.projectToolOutput(once, 4000);

        var view = twice.path("data").path("item");
        // 二次投影不改变已修正的可见范围，不把 originalThroughOffset 当成新的可见终点
        assertThat(view.path("throughOffset").asInt()).isEqualTo(300);
        assertThat(view.path("originalThroughOffset").asInt()).isEqualTo(2200);
        assertThat(view.path("omittedChars").asInt()).isEqualTo(1900);
    }

    @Test
    void planningProjectionLabelsOriginalCharsSemantics() {
        var output = json.createObjectNode();
        var data = output.putObject("data");
        data.put("baseVersionId", UUID.randomUUID().toString());
        var draft = data.putObject("draft");
        var tasks = draft.putArray("tasks");
        for (int i = 0; i < 10; i++) {
            var task = tasks.addObject();
            task.put("tempKey", "t" + i);
            task.put("title", "任务".repeat(150) + i);
        }
        var projected = projector.projectToolOutput(output, 1500);
        assertThat(projected.path("originalCharsSemantics").asText())
                .isEqualTo("SERIALIZED_TOOL_RESULT_JSON_CHARS_NOT_BODY_LENGTH");
        assertThat(projected.path("projection").asText()).isEqualTo("DETERMINISTIC");
    }
}
