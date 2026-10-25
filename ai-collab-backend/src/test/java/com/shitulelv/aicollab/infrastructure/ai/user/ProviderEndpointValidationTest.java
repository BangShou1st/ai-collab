package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义 Provider 端点校验：{model} 占位符放行 + 出站安全策略不放松。
 */
class ProviderEndpointValidationTest {
    private UserAiProviderRequest req(String baseUrl, String apiPath) {
        return new UserAiProviderRequest("c", ModelProviderType.GEMINI, baseUrl, apiPath,
                "k", "gemini-flash", true, 0.2, 1200, Set.of(ModelCapability.CHAT, ModelCapability.STREAMING));
    }

    private UserAiProviderService svc(UserAiProviderRepository repo, OutboundEndpointPolicy policy) {
        return new UserAiProviderService(repo, mock(ModelSecretCipher.class), policy, List.of());
    }

    private UserAiProviderRepository saving() {
        UserAiProviderRepository repo = mock(UserAiProviderRepository.class);
        when(repo.countByUserId(any())).thenReturn(0);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return repo;
    }

    @Test void geminiModelPlaceholderPathIsAccepted() {
        var repo = saving();
        svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent"));
        verify(repo).save(any());
    }

    @Test void repeatedPlaceholderIsRejected() {
        var repo = saving();
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://generativelanguage.googleapis.com", "/v1beta/{model}/{model}:generateContent")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("{model}");
        verify(repo, never()).save(any());
    }

    @Test void unsupportedPlaceholderExpressionsAreRejected() {
        var repo = saving();
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://generativelanguage.googleapis.com", "/v1beta/models/{modelId}:generateContent")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("{model}");
        verify(repo, never()).save(any());
    }

    @Test void unbalancedBracesAreRejected() {
        var repo = saving();
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://generativelanguage.googleapis.com", "/v1beta/models/{model:generateContent")))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test void unsafeBaseUrlsKeepBeingRejectedWithActionableMessage() {
        var repo = saving();
        // requirePublicHttps 拒绝内网地址：真实策略对象验证端到端行为
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("http://127.0.0.1:8080", "/v1/chat/completions")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("出站安全策略");
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://10.0.0.5", "/v1/chat/completions")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("出站安全策略");
    }

    @Test void absoluteApiPathIsRejectedWithActionableMessage() {
        var repo = saving();
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://api.example.com", "https://api.example.com/v1/chat/completions")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相对路径");
    }

    @Test void legacyHardcodedGeminiPathStillValid() {
        // 历史配置把模型名硬编码进 path（无占位符）；该形态必须继续可用。
        var repo = saving();
        svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://generativelanguage.googleapis.com", "/v1beta/models/gemini-flash:generateContent"));
        verify(repo).save(any());
    }

    @Test void duplicatedVersionPrefixIsRejectedBeforeAnyRequest() {
        // T04：Base URL 带 /v1 + 默认 API Path 以 /v1 开头 → 保存前即拒绝并给出修正方向
        var repo = saving();
        assertThatThrownBy(() -> svc(repo, new OutboundEndpointPolicy()).create(UUID.randomUUID(),
                req("https://api-inference.modelscope.cn/v1", "/v1/chat/completions")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复版本前缀")
                .hasMessageContaining("/v1/v1");
        verify(repo, never()).save(any());
    }

    @Test void profilesCoverAllCustomProviderTypes() {
        var types = ProviderProfileCatalog.all().stream().map(ProviderProfileCatalog.ProviderProfile::providerType).toList();
        assertThat(types).contains(ModelProviderType.OPENAI_COMPATIBLE,
                ModelProviderType.ANTHROPIC, ModelProviderType.GEMINI);
        // 档案 code 唯一；ModelScope 兼容网关档案存在且走 OPENAI_COMPATIBLE 协议
        var codes = ProviderProfileCatalog.all().stream().map(ProviderProfileCatalog.ProviderProfile::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
        var modelscope = ProviderProfileCatalog.ofCode("MODELSCOPE").orElseThrow();
        assertThat(modelscope.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(modelscope.defaultBaseUrl()).isEqualTo("https://api-inference.modelscope.cn");
        assertThat(modelscope.defaultApiPath()).isEqualTo("/v1/chat/completions");
        var gemini = ProviderProfileCatalog.of(ModelProviderType.GEMINI).orElseThrow();
        assertThat(gemini.defaultBaseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
        assertThat(gemini.defaultApiPath()).isEqualTo("/v1beta/models/{model}:generateContent");
        assertThat(gemini.candidateCapabilities()).contains(ModelCapability.CHAT, ModelCapability.STREAMING);
        // 表单默认候选必须包含 STREAMING，否则 UI 新建配置无法用于流式知识问答。
        for (ProviderProfileCatalog.ProviderProfile profile : ProviderProfileCatalog.all()) {
            assertThat(profile.candidateCapabilities()).contains(ModelCapability.CHAT, ModelCapability.STREAMING);
        }
    }
}
