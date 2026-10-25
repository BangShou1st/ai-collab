package com.shitulelv.aicollab.infrastructure.ai.user;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base URL / API Path 组合规则：重复版本前缀识别、修正建议与端点预览。
 * 对应 P7-PROVIDER-ONBOARDING-01 任务 A1 / 验收场景 T04。
 */
class ProviderEndpointRulesTest {

    @Test void duplicatedVersionPrefixIsDetected() {
        // P7 真实验收复现：Base URL 带 /v1，默认 API Path 又以 /v1 开头 → /v1/v1/models
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://api-inference.modelscope.cn/v1", "/v1/chat/completions"))
                .contains("v1");
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://gw.example.com/api/v2", "/v2/chat/completions"))
                .contains("v2");
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://gw.example.com/v1/", "/v1/chat/completions"))
                .contains("v1");
    }

    @Test void legitimatePathsAreNotFlagged() {
        // Gemini 官方：Base 无版本段 + /v1beta 路径 —— 合法
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent"))
                .isEmpty();
        // Anthropic 官方
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://api.anthropic.com", "/v1/messages"))
                .isEmpty();
        // 常规 OpenAI 兼容
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://api.openai.com", "/v1/chat/completions"))
                .isEmpty();
        // 特殊自定义网关：非版本段重复不误伤（不做通用路径去重）
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://gw.example.com/inference", "/inference/chat/completions"))
                .isEmpty();
        // 版本段不同（v1 vs v2）不构成重复
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://gw.example.com/v1", "/v2/chat/completions"))
                .isEmpty();
        // 路径不以 / 开头（交由保存校验报错，规则本身不判定）
        assertThat(ProviderEndpointRules.duplicateVersionJunction(
                "https://gw.example.com/v1", "v1/chat/completions"))
                .isEmpty();
    }

    @Test void fixSuggestionsStripExactlyOneSide() {
        assertThat(ProviderEndpointRules.stripDuplicateFromPath(
                "https://api-inference.modelscope.cn/v1", "/v1/chat/completions"))
                .contains("/chat/completions");
        assertThat(ProviderEndpointRules.stripDuplicateFromBase(
                "https://api-inference.modelscope.cn/v1", "/v1/chat/completions"))
                .contains("https://api-inference.modelscope.cn/");
        // 无重复时不给修正建议
        assertThat(ProviderEndpointRules.stripDuplicateFromPath(
                "https://api.openai.com", "/v1/chat/completions")).isEmpty();
    }

    @Test void inferencePreviewComposesBaseAndPath() {
        assertThat(ProviderEndpointRules.inferenceUrlPreview(
                "https://api.example.com/", "/v1/chat/completions"))
                .isEqualTo("https://api.example.com/v1/chat/completions");
        assertThat(ProviderEndpointRules.inferenceUrlPreview(
                "https://api.example.com", "v1/chat/completions"))
                .isEqualTo("https://api.example.com/v1/chat/completions");
    }

    @Test void duplicatedJunctionPreviewShowsTheFault() {
        // 拼接预览直观暴露 /v1/v1 —— 供 UI 在发起请求前展示
        assertThat(ProviderEndpointRules.inferenceUrlPreview(
                "https://api-inference.modelscope.cn/v1", "/v1/chat/completions"))
                .isEqualTo("https://api-inference.modelscope.cn/v1/v1/chat/completions");
    }
}
