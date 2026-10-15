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
 * 出站防护（DIRECT、无代理回退）保持不变。
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

        assertThat(registry.zenBaseUrl()).isEqualTo("https://opencode.ai/inference/v1");
        assertThat(registry.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/models");
        assertThat(registry.completionEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/chat/completions");
    }

    @Test
    void explicitLegacyUpstreamRoutesModelsChatAndConnectionTestToTheSameBase() {
        ProviderPresetRegistry registry = new ProviderPresetRegistry("https://opencode.ai/zen/v1/");
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        ZenModelExecution exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());
        HttpOpenCodeZenTransport transport = new HttpOpenCodeZenTransport(mapper, new OutboundEndpointPolicy(), registry);

        // 旧版兼容端点被显式选择时，模型列表、推理、连接测试用的策略同样来自这一个上游。
        assertThat(registry.zenBaseUrl()).isEqualTo("https://opencode.ai/zen/v1");
        assertThat(registry.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/zen/v1/models");
        assertThat(exec.runtimeEndpoint()).isEqualTo("https://opencode.ai/zen/v1/chat/completions");
        assertThat(transport.policy().baseUrl()).isEqualTo(registry.zenBaseUrl());
        assertThat(transport.policy().completionPath()).isEqualTo(pol.completionPath());
    }

    @Test
    void onlyWhitelistedHttpsZenBasesAreAccepted() {
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("https://evil.example/v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream("http://opencode.ai/inference/v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProviderPresetRegistry.approveZenUpstream(""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ProviderPresetRegistry.approveZenUpstream("https://opencode.ai/inference/v1/"))
                .isEqualTo("https://opencode.ai/inference/v1");
        assertThat(ProviderPresetRegistry.approveZenUpstream("https://opencode.ai/zen/v1/"))
                .isEqualTo("https://opencode.ai/zen/v1");
        assertThat(new ProviderPresetRegistry().approvedBaseUrls())
                .containsExactlyInAnyOrder("https://opencode.ai/inference/v1", "https://opencode.ai/zen/v1");
    }

    @Test
    void persistedProviderBaseUrlCannotOverrideTheSelectedUpstream() {
        for (String configured : new String[]{"https://opencode.ai/inference/v1", "https://opencode.ai/zen/v1"}) {
            ProviderPresetRegistry registry = new ProviderPresetRegistry(configured);
            ZenModelExecution exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());

            var runtime = exec.runtimeConfig(providerWithPersistedUrls("https://evil.example/override", "/evil"));

            assertThat(runtime.baseUrl()).isEqualTo(configured);
            assertThat(runtime.apiPath()).isEqualTo("/chat/completions");
        }
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

        assertThat(HttpOpenCodeZenTransport.isDirect(transport.productionClient())).isTrue();
        assertThat(HttpOpenCodeZenTransport.directSelector()).isEqualTo(java.net.http.HttpClient.Builder.NO_PROXY);
        assertThat(HttpOpenCodeZenTransport.directProxy()).isEqualTo(java.net.Proxy.NO_PROXY);
    }
}
