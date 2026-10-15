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
 * 模型级 provider.npm 优先、服务商级 opencode.npm 回退（真实目录形状）、
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

    /**
     * 回归：真实可信目录把协议声明在服务商级 {@code opencode.npm}，模型条目<b>没有</b> provider 字段。
     * 目录中 {@code big-pickle} 价格为零（{@code cost.input=0}、{@code cost.output=0}），
     * 必须靠服务商级 npm 判定为 Chat 并入选，而不是被判为 UNKNOWN 排除。
     *
     * <p>目录形状取自 {@code https://models.opencode.ai/api.json}：
     * {@code {"opencode":{"npm":"@ai-sdk/openai-compatible","models":{"big-pickle":{...}}}}}。</p>
     */
    @Test
    void zeroPriceModelWithoutModelLevelNpmUsesProviderLevelNpmFallback() {
        ObjectNode trusted = trustedWith("@ai-sdk/openai-compatible");
        entry(trusted, "big-pickle", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("big-pickle"), trusted);

        assertThat(selection.models()).containsExactly("big-pickle");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    /** 模型级 provider.npm 优先于服务商级 opencode.npm：模型声明 Chat，服务商声明 Responses。 */
    @Test
    void modelLevelNpmTakesPrecedenceOverProviderLevelNpm() {
        ObjectNode trusted = trustedWith("@ai-sdk/openai");
        entry(trusted, "chatty-free", model -> {
            model.putObject("cost").put("input", 0).put("output", 0);
            model.putObject("provider").put("npm", "@ai-sdk/openai-compatible");
        });

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("chatty-free"), trusted);

        assertThat(selection.models()).containsExactly("chatty-free");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    /** 模型级声明 Anthropic 时不得被服务商级 Chat 回退覆盖：模型级优先，仍然拒绝。 */
    @Test
    void modelLevelAnthropicIsNotOverriddenByProviderLevelChat() {
        ObjectNode trusted = trustedWith("@ai-sdk/openai-compatible");
        entry(trusted, "claude-free", model -> {
            model.putObject("cost").put("input", 0).put("output", 0);
            model.putObject("provider").put("npm", "@ai-sdk/anthropic");
        });

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("claude-free"), trusted);

        assertThat(selection.models()).isEmpty();
        assertThat(selection.unsupportedProtocols()).containsEntry("claude-free", ZenModelProtocol.ANTHROPIC);
    }

    /**
     * 服务商级回退不得放宽安全限制：服务商级 npm 指向 Responses / Anthropic 时，
     * 缺少模型级字段的零价格模型仍被拒绝，{@code -free} 也不作为放行理由。
     */
    @Test
    void providerLevelNpmFallbackStillRejectsResponsesAndAnthropic() {
        ObjectNode responsesCatalog = trustedWith("@ai-sdk/openai");
        entry(responsesCatalog, "muse-spark-free", model -> model.putObject("cost").put("input", 0).put("output", 0));
        ObjectNode anthropicCatalog = trustedWith("@ai-sdk/anthropic");
        entry(anthropicCatalog, "claude-free", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection responses = ZenFreeModelSelection.select(upstream("muse-spark-free"), responsesCatalog);
        ZenFreeModelSelection anthropic = ZenFreeModelSelection.select(upstream("claude-free"), anthropicCatalog);

        assertThat(responses.models()).isEmpty();
        assertThat(responses.unsupportedProtocols()).containsEntry("muse-spark-free", ZenModelProtocol.RESPONSES);
        assertThat(anthropic.models()).isEmpty();
        assertThat(anthropic.unsupportedProtocols()).containsEntry("claude-free", ZenModelProtocol.ANTHROPIC);
    }

    /** 服务商级 npm 无法判定协议时仍不做猜测：无后缀零价格模型保持 UNKNOWN 并被排除。 */
    @Test
    void providerLevelNpmThatIsUnrecognizedDoesNotMakeModelExecutable() {
        ObjectNode trusted = trustedWith("@ai-sdk/some-unknown-provider");
        entry(trusted, "mystery", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("mystery"), trusted);

        assertThat(selection.models()).isEmpty();
        assertThat(selection.unsupportedProtocols()).containsEntry("mystery", ZenModelProtocol.UNKNOWN);
    }

    /** 目录变体：provider 块嵌套在 {@code opencode.provider.npm} 下时，作为显式兼容分支读取。 */
    @Test
    void nestedProviderNpmVariantIsReadAsFallback() {
        ObjectNode trusted = trustedWith();
        ((ObjectNode) trusted.path("opencode")).putObject("provider").put("npm", "@ai-sdk/openai-compatible");
        entry(trusted, "big-pickle", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("big-pickle"), trusted);

        assertThat(selection.models()).containsExactly("big-pickle");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    /** {@code opencode.npm} 存在时优先于嵌套的 {@code opencode.provider.npm}。 */
    @Test
    void directProviderLevelNpmWinsOverNestedVariant() {
        ObjectNode trusted = trustedWith("@ai-sdk/openai-compatible");
        ((ObjectNode) trusted.path("opencode")).putObject("provider").put("npm", "@anthropic-ai/sdk");
        entry(trusted, "big-pickle", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("big-pickle"), trusted);

        assertThat(selection.models()).containsExactly("big-pickle");
        assertThat(selection.unsupportedProtocols()).isEmpty();
    }

    /**
     * 记录 {@code -free} 回退的边界：目录里<b>任何位置</b>都没有 npm 声明时，
     * 免费后缀模型按约定走 Chat（与改造前一致），这是向后兼容的假定而非协议验证。
     */
    @Test
    void freeSuffixWithoutAnyNpmDeclarationFallsBackToChatByConvention() {
        ObjectNode trusted = trustedWith();
        entry(trusted, "legacy-free", model -> model.putObject("cost").put("input", 0).put("output", 0));

        ZenFreeModelSelection selection = ZenFreeModelSelection.select(upstream("legacy-free"), trusted);

        assertThat(selection.models()).containsExactly("legacy-free");
        assertThat(selection.unsupportedProtocols()).isEmpty();
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
        return trustedWith(null);
    }

    /** 真实目录形状：服务商级 npm 位于 {@code opencode.npm}，与 {@code opencode.models} 同级。 */
    private ObjectNode trustedWith(String providerNpm) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode opencode = root.putObject("opencode");
        opencode.putObject("models");
        if (providerNpm != null) {
            opencode.put("npm", providerNpm);
        }
        return root;
    }

    private void entry(ObjectNode trusted, String modelId, Consumer<ObjectNode> properties) {
        ObjectNode model = (ObjectNode) trusted.path("opencode").path("models");
        properties.accept(model.putObject(modelId));
    }
}
