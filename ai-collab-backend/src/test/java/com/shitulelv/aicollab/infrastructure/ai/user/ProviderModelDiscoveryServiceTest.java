package com.shitulelv.aicollab.infrastructure.ai.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义 Provider 模型发现：URL 推导、响应解析、预设/停用拒绝与缓存。
 */
class ProviderModelDiscoveryServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private UserAiProvider provider(ModelProviderType type, String baseUrl, String apiPath) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "c", type, baseUrl, apiPath,
                "enc", "m", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test void openAiCompatibleDiscoveryUrlDerivesFromChatCompletionsPath() {
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/chat/completions")))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.OPENAI_COMPATIBLE, "https://gw.example.com/", "/api/v1/chat/completions")))
                .isEqualTo("https://gw.example.com/api/v1/models");
    }

    @Test void anthropicDiscoveryUrlDerivesFromMessagesPath() {
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.ANTHROPIC, "https://api.anthropic.com", "/v1/messages")))
                .isEqualTo("https://api.anthropic.com/v1/models");
    }

    @Test void geminiDiscoveryUrlTruncatesAtModelsSegment() {
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com",
                        "/v1beta/models/{model}:generateContent")))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        // 历史硬编码模型名的 path 同样能推导出发现端点
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com",
                        "/v1beta/models/gemini-flash:generateContent")))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
    }

    @Test void unknownPathFallsBackToBaseModels() {
        assertThat(ProviderModelDiscoveryService.discoveryUrl(
                provider(ModelProviderType.OPENAI_COMPATIBLE, "https://gw.example.com", "/infer")))
                .isEqualTo("https://gw.example.com/models");
    }

    @Test void parsesOpenAiAndAnthropicAndGeminiShapes() throws Exception {
        assertThat(ProviderModelDiscoveryService.parseModels(ModelProviderType.OPENAI_COMPATIBLE,
                JSON.readTree("{\"data\":[{\"id\":\"gpt-x\"},{\"id\":\"gpt-y\"}]}")))
                .containsExactly("gpt-x", "gpt-y");
        assertThat(ProviderModelDiscoveryService.parseModels(ModelProviderType.ANTHROPIC,
                JSON.readTree("{\"data\":[{\"id\":\"claude-x\"}]}")))
                .containsExactly("claude-x");
        assertThat(ProviderModelDiscoveryService.parseModels(ModelProviderType.GEMINI,
                JSON.readTree("{\"models\":[{\"name\":\"models/gemini-flash\"},{\"name\":\"models/gemini-pro\"}]}")))
                .containsExactly("gemini-flash", "gemini-pro");
    }

    @Test void presetsCannotUseCustomDiscoveryChannel() {
        var preset = new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "Zen", ModelProviderType.OPENAI_COMPATIBLE,
                "https://opencode.ai/inference/v1", "/chat/completions", null, "m", true, 0.2, 4000,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
        var repo = mock(UserAiProviderRepository.class);
        when(repo.findByIdAndUserId(preset.id(), preset.userId())).thenReturn(Optional.of(preset));
        var service = new ProviderModelDiscoveryService(repo, mock(UserAiProviderService.class), mock(ModelSecretCipher.class), mock(JsonHttpModelClient.class));
        assertThatThrownBy(() -> service.models(preset.userId(), preset.id(), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预置连接");
    }

    @Test void resultsAreCachedUntilRefresh() throws Exception {
        var provider = provider(ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/chat/completions");
        var repo = mock(UserAiProviderRepository.class);
        when(repo.findByIdAndUserId(provider.id(), provider.userId())).thenReturn(Optional.of(provider));
        var secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt(anyString())).thenReturn("key");
        var http = mock(JsonHttpModelClient.class);
        when(http.get(anyString(), any())).thenReturn(JSON.readTree("{\"data\":[{\"id\":\"gpt-x\"}]}"));
        var service = new ProviderModelDiscoveryService(repo, mock(UserAiProviderService.class), secrets, http);
        assertThat(service.models(provider.userId(), provider.id(), false)).containsExactly("gpt-x");
        assertThat(service.models(provider.userId(), provider.id(), false)).containsExactly("gpt-x");
        verify(http, times(1)).get(anyString(), any());
        service.models(provider.userId(), provider.id(), true);
        verify(http, times(2)).get(anyString(), any());
    }

    @Test void emptyListingFailsWithActionableMessage() throws Exception {
        var provider = provider(ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/chat/completions");
        var repo = mock(UserAiProviderRepository.class);
        when(repo.findByIdAndUserId(provider.id(), provider.userId())).thenReturn(Optional.of(provider));
        var secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt(anyString())).thenReturn("key");
        var http = mock(JsonHttpModelClient.class);
        when(http.get(anyString(), any())).thenReturn(JSON.readTree("{\"data\":[]}"));
        var service = new ProviderModelDiscoveryService(repo, mock(UserAiProviderService.class), secrets, http);
        assertThatThrownBy(() -> service.models(provider.userId(), provider.id(), true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手动填写");
    }
}
