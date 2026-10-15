package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 免费可执行模型选择规则（纯函数，不触网）。
 * 覆盖：零价格无后缀模型入选、已知收费模型不被 -free 蒙混、退役模型排除、
 * 非 Chat 协议与未知协议排除、可信目录不可用时的保守回退。
 */
class ZenFreeModelSelectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void trustedCatalogUnavailableKeepsOnlySuffixFreeModels() {
        JsonNode upstream = upstream("a-free", "big-pickle");

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream, null);

        // 保守回退：不把价格未知的无后缀模型当作免费。
        assertThat(selection.models()).containsExactly("a-free");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    @Test
    void zeroPriceModelWithoutFreeSuffixIsIncluded() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "big-pickle", model -> {
            model.putObject("cost").put("input", 0).put("output", 0);
            model.putObject("provider").put("npm", "@ai-sdk/openai-compatible");
        });

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("big-pickle"), trusted);

        assertThat(selection.models()).containsExactly("big-pickle");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    @Test
    void positivePriceModelIsNotFreeEvenWithFreeSuffix() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "trap-free", model -> model.putObject("cost").put("input", 1).put("output", 2));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("trap-free"), trusted);

        assertThat(selection.models()).isEmpty();
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    @Test
    void retiredOrDisabledModelsAreExcluded() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "deprecated-free", model -> model.put("deprecated", true));
        entry(trusted, "retired-free", model -> model.put("status", "retired"));
        entry(trusted, "disabled-free", model -> model.put("lifecycle", "disabled"));
        entry(trusted, "sunset-free", model -> model.put("retirement_date", "2030-01-01"));
        entry(trusted, "live-free", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(
                upstream("deprecated-free", "retired-free", "disabled-free", "sunset-free", "live-free"), trusted);

        assertThat(selection.models()).containsExactly("live-free");
    }

    @Test
    void modelsAbsentFromUpstreamListAreNotIncluded() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "not-listed", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("other-free"), trusted);

        assertThat(selection.models()).containsExactly("other-free");
        assertThat(selection.unsupportedProtocols()).doesNotContainKey("not-listed");
    }

    @Test
    void nonChatProtocolModelsAreReportedAsUnsupportedInsteadOfExecutable() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "muse-spark-free", model -> {
            model.putObject("cost").put("input", 0).put("output", 0);
            model.putObject("provider").put("npm", "@ai-sdk/openai");
        });
        entry(trusted, "claude-free", model -> {
            model.putObject("cost").put("input", 0).put("output", 0);
            model.putObject("provider").put("npm", "@anthropic-ai/sdk");
        });

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(
                upstream("muse-spark-free", "claude-free"), trusted);

        assertThat(selection.models()).isEmpty();
        assertThat(selection.unsupportedProtocols())
                .containsEntry("muse-spark-free", ZenModelProtocol.RESPONSES)
                .containsEntry("claude-free", ZenModelProtocol.ANTHROPIC);
    }

    @Test
    void zeroPriceModelWithUnknownProtocolIsNotExecutable() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "mystery", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("mystery"), trusted);

        // 协议无法判定：不提供给用户选择，但记录原因以便配置阶段明确拒绝。
        assertThat(selection.models()).isEmpty();
        assertThat(selection.unsupportedProtocols()).containsEntry("mystery", ZenModelProtocol.UNKNOWN);
    }

    @Test
    void modelWithUnknownPriceAndNoSuffixIsExcluded() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "plain", model -> model.put("name", "Plain"));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("plain", "kept-free"), trusted);

        assertThat(selection.models()).containsExactly("kept-free");
    }

    @Test
    void malformedUpstreamListingYieldsEmptySelection() {
        assertThat(ZenFreeModelSelection.select(null, null)).isEqualTo(ZenFreeModelSelection.empty());
        assertThat(ZenFreeModelSelection.select(mapper.createObjectNode(), null).models()).isEmpty();
    }

    private JsonNode upstream(String... ids) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (String id : ids) {
            data.addObject().put("id", id);
        }
        return root;
    }

    private ObjectNode trustedWith() {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("opencode").putObject("models");
        return root;
    }

    private void entry(ObjectNode trusted, String modelId, Consumer<ObjectNode> properties) {
        ObjectNode model = (ObjectNode) trusted.path("opencode").path("models");
        properties.accept(model.putObject(modelId));
    }
}
