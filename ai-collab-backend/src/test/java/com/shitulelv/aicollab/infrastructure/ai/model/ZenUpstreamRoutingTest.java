package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderPresetController;
import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zen 上游端点管理：模型列表、连接测试与正式推理必须路由到同一个被选择的上游；
 * 上游只能是代码白名单内的 HTTPS Zen 地址，且不能被用户请求 / 持久化 baseUrl 覆盖；
 * 出站防护（HTTPS 白名单、禁止重定向）保持不变；代理统一交给共享网络策略。
 */
class ZenUpstreamRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private UserAiProvider providerWithPersistedUrls(String baseUrl, String apiPath) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, baseUrl, apiPath, "enc", "big-pickle", true,
                0.2, 1200, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }

    @Test
    void defaultUpstreamIsTheNewOfficialInferenceEndpoint() {
        ProviderPresetRegistry registry = new ProviderPresetRegistry();
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);

        assertThat(pol.baseUrl()).isEqualTo("https://opencode.ai/inference/v1");
        assertThat(registry.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/models");
        assertThat(registry.completionEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/chat/completions");
    }

    @Test
    void legacyDiscoveryBaseIsNotAnAcceptedInferenceUpstream() {
        // 共享库明确：/zen/v1 只允许发现、绝不允许推理，因此不能作为可选上游。
        assertThatThrownBy(() -> new ProviderPresetRegistry("https://opencode.ai/zen/v1/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyWhitelistedHttpsZenBasesAreAccepted() {
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("https://evil.example/v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("http://opencode.ai/inference/v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream(""))
                .isInstanceOf(IllegalStateException.class);
        // 旧 base 与旧版发现端点都不再获批为推理上游：白名单只含共享库声明的推理 base。
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("https://opencode.ai/inference"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("https://opencode.ai/zen/v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ProviderPresetRegistry.approveZenUpstream("https://opencode.ai/inference/v1/"))
                .isEqualTo("https://opencode.ai/inference/v1");
        assertThat(new ProviderPresetRegistry().require(ProviderPresetCode.OPENCODE_ZEN_FREE).baseUrl())
                .isEqualTo("https://opencode.ai/inference/v1");
    }

    @Test
    void persistedProviderBaseUrlCannotOverrideTheSelectedUpstream() {
        ProviderPresetRegistry registry = new ProviderPresetRegistry("https://opencode.ai/inference/v1");
        ZenModelExecution exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());

        var runtime = exec.runtimeConfig(providerWithPersistedUrls("https://evil.example/override", "/evil"));

        assertThat(runtime.baseUrl()).isEqualTo("https://opencode.ai/inference/v1");
        assertThat(runtime.apiPath()).isEqualTo("/chat/completions");
    }

    @Test
    void presetSaveRequestCannotCarryABaseUrl() {
        assertThat(Arrays.stream(UserAiProviderPresetController.SaveZenRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactlyInAnyOrder("apiKey", "modelName", "enabled", "setDefault")
                .doesNotContain("baseUrl", "apiPath");
    }

    @Test
    void outboundProtectionIsNotRelaxedForZen() {
        HttpOpenCodeZenTransport transport = new HttpOpenCodeZenTransport(
                mapper, new OutboundEndpointPolicy(), new ProviderPresetRegistry());

        assertThat(transport.productionClient().proxy().orElseThrow())
                .isInstanceOf(com.shitulelv.aicollab.common.net.EnvironmentProxySelector.class);
        assertThat(transport.productionClient().followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }
}
