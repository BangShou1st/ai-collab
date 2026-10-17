package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZenModelWiringTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private UserAiProvider zenProvider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://evil.example/override", "/evil",
                "enc", "mimo-test-free", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                false, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }
    private UserAiProvider customProvider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "custom",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "gpt-4o-mini", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT),
                false, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }
    @Test void registryIsSingleTrustSource() {
        ProviderPresetRegistry reg = new ProviderPresetRegistry();
        var pol = reg.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        assertThat(pol.protocol()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(reg.completionEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/chat/completions");
        assertThat(reg.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/models");
        assertThat(pol.userAgent()).isEqualTo("opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14");
    }
    @Test void runtimeConfigIgnoresPersistedUrls() {
        ZenModelExecution exec = new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy());
        var cfg = exec.runtimeConfig(zenProvider());
        assertThat(cfg.baseUrl()).isEqualTo("https://opencode.ai/inference/v1");
        assertThat(cfg.apiPath()).isEqualTo("/chat/completions");
        assertThat(cfg.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(cfg.modelName()).isEqualTo("mimo-test-free");
    }
    @Test void zenHttpIsDirectAndCustomUnaffected() {
        var registry = new ProviderPresetRegistry();
        ZenModelExecution exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());
        assertThat(exec.zenHttp()).isNotNull();
        HttpOpenCodeZenTransport t = new HttpOpenCodeZenTransport(mapper, new OutboundEndpointPolicy(), registry);
        assertThat(t.productionClient().proxy().orElse(null)).isEqualTo(HttpClient.Builder.NO_PROXY);
        assertThat(exec.isZen(zenProvider())).isTrue();
        assertThat(exec.isZen(customProvider())).isFalse();
    }
    @Test void samePolicyDrivesModelsTestAndInference() {
        var registry = new ProviderPresetRegistry();
        var pol = registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        HttpOpenCodeZenTransport t = new HttpOpenCodeZenTransport(mapper, new OutboundEndpointPolicy(), registry);
        ZenModelExecution exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());
        assertThat(registry.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/inference/v1/models");
        assertThat(exec.runtimeEndpoint()).isEqualTo(registry.completionEndpoint(pol));
        assertThat(t.policy().userAgent()).isEqualTo(pol.userAgent());
        // 身份头（User-Agent / ses_ / msg_ / x-opencode-*）统一由共享请求工厂生成；
        // 端到端 wire 断言见 ModelProtocolHttpTest，这里只核对同一策略驱动发现与推理。
    }
    @Test void admittedListingIsTheReviewedWhitelistIntersectedWithTheLiveUpstream() {
        // 上游仍在服务白名单内的两个模型；非白名单模型与白名单内但上游已下线的模型都被排除。
        assertThat(HttpOpenCodeZenTransport.intersectWithAdmitted(
                List.of("big-pickle", "space-bunny-free", "some-new-free", "gpt-4")))
                .containsExactly("big-pickle", "space-bunny-free");
        // 上游不可用：保守退回已评审白名单本身（准入始终以白名单为准，不臆测可用性）。
        assertThat(HttpOpenCodeZenTransport.intersectWithAdmitted(null))
                .containsExactly("big-pickle", "longcat-2.5-preview-free", "mimo-v2.6-flash-free",
                        "nemotron-3.5-lightning-free", "space-bunny-free");
    }
    @Test void turnRoutingKeepsStableSession() {
        var provider = zenProvider();
        var svc = mock(com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService.class);
        when(svc.resolve(any(), any())).thenReturn(provider);
        var secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt(any())).thenReturn("k");
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(true);
        var expected = mock(ModelTurnResult.class);
        when(zen.turn(any(), any(), any(), any())).thenReturn(expected);
        var gw = new RoutingModelTurnGateway(mock(ModelConfigurationRepository.class), svc, secrets, List.of(), zen);
        var cmd = new ModelTurnCommand(ModelPurpose.AGENT, null, null, List.of(new ModelMessage.User("hi")), List.of(), false, provider.userId());
        var md = AiRequestMetadata.of("stable-session");
        assertThat(gw.turn(cmd, md)).isEqualTo(expected);
        org.mockito.Mockito.verify(zen).turn(any(), any(), any(), org.mockito.ArgumentMatchers.argThat(m -> ((AiRequestMetadata)m).correlationSessionId().equals("stable-session")));
    }
}
