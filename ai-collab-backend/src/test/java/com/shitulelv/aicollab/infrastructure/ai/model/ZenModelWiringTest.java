package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
        assertThat(reg.completionEndpoint(pol)).isEqualTo("https://opencode.ai/zen/v1/chat/completions");
        assertThat(reg.modelsEndpoint(pol)).isEqualTo("https://opencode.ai/zen/v1/models");
        assertThat(pol.userAgent()).isEqualTo("opencode/1.18.21");
    }
    @Test void runtimeConfigIgnoresPersistedUrls() {
        ZenModelExecution exec = new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy());
        var cfg = exec.runtimeConfig(zenProvider());
        assertThat(cfg.baseUrl()).isEqualTo("https://opencode.ai/zen/v1");
        assertThat(cfg.apiPath()).isEqualTo("/chat/completions");
        assertThat(cfg.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(cfg.modelName()).isEqualTo("mimo-test-free");
    }
    @Test void zenHttpIsDirectAndCustomUnaffected() {
        ZenModelExecution exec = new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy());
        assertThat(exec.zenHttp()).isNotNull();
        HttpOpenCodeZenTransport t = new HttpOpenCodeZenTransport(mapper, new OutboundEndpointPolicy());
        assertThat(t.productionClient().proxy().orElse(null)).isEqualTo(HttpClient.Builder.NO_PROXY);
        assertThat(exec.isZen(zenProvider())).isTrue();
        assertThat(exec.isZen(customProvider())).isFalse();
    }
    @Test void sessionHeadersExact() {
        var md = AiRequestMetadata.of("sess-123");
        Map<String,String> h = OpenAiCompatibleModelAdapter.headersWithSession("k", md);
        assertThat(h.get("Authorization")).isEqualTo("Bearer k");
        assertThat(h.get("User-Agent")).isEqualTo("opencode/1.18.21");
        assertThat(h.get("x-opencode-session")).isEqualTo("sess-123");
        assertThat(h.values().stream().noneMatch(v -> v.contains("k-secret"))).isTrue();
    }
    @Test void catalogOnlyFree() {
        ObjectNode root = mapper.createObjectNode();
        var arr = root.putArray("data");
        arr.addObject().put("id", "a-free");
        arr.addObject().put("id", "b-paid");
        arr.addObject().put("id", "c-free");
        OpenCodeZenTransport stub = new OpenCodeZenTransport() {
            public com.fasterxml.jackson.databind.JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) { return root; }
            public java.util.List<String> listFreeModels(String apiKey, AiRequestMetadata metadata) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (var n : root.path("data")) { String id = n.path("id").asText(null); if (id != null && id.endsWith("-free")) out.add(id); }
                return out;
            }
            public void validateCredential(String apiKey, String model, AiRequestMetadata metadata) { }
        };
        OpenCodeZenModelCatalog catalog = new OpenCodeZenModelCatalog(stub);
        var free = catalog.freeModels(null, AiRequestMetadata.of("s"));
        assertThat(free).containsExactlyInAnyOrder("a-free", "c-free");
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
