package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ZenPlanningContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ModelConfiguration zenConfig() {
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://opencode.ai/zen/v1", "/chat/completions",
                null, "mimo-v2.5-free", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ChatCompletionCommand jsonCommand() {
        return new ChatCompletionCommand(UUID.randomUUID(), "system", "{\"a\":1}",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT, ModelPurpose.PLANNING, null, List.of(), UUID.randomUUID());
    }

    @Test void zenForcedJsonWireHasNoResponseFormat() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        final JsonNode[] captured = new JsonNode[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(2);
            BiConsumer<String, JsonNode> cb = inv.getArgument(3);
            ObjectNode chunk = mapper.createObjectNode();
            chunk.putArray("choices").addObject().putObject("delta").put("content", "{\"ok\":true}");
            cb.accept("message", chunk);
            ObjectNode done = mapper.createObjectNode();
            done.putArray("choices").addObject().put("finish_reason", "stop");
            cb.accept("message", done);
            return null;
        }).when(http).stream(anyString(), anyMap(), any(JsonNode.class), any(BiConsumer.class));
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(mapper, http);
        ChatCompletionCommand forced = ZenModelExecution.forceJson(jsonCommand());
        ChatCompletionResult result = adapter.completeStreamingSyncWithSession(
                zenConfig(), "k", forced, AiRequestMetadata.of("corr"), "opencode/1.18.21");
        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(captured[0].path("stream").asBoolean()).isTrue();
        assertThat(captured[0].has("response_format")).isFalse();
        assertThat(forced.outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.TEXT);
    }

    @Test void planningGatePassesForZenRuntimeConfig() {
        var registry = new ProviderPresetRegistry();
        var exec = new ZenModelExecution(registry, mapper, new OutboundEndpointPolicy());
        var stale = new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://evil.example", "/evil", "enc", "mimo-v2.5-free",
                true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT), false,
                OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
        ModelConfiguration runtime = exec.runtimeConfig(stale);
        assertThat(runtime.capabilities()).contains(ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS);
        ModelCapabilityPolicy.require(runtime, jsonCommand(), false);
        assertThat(runtime.baseUrl()).isEqualTo("https://opencode.ai/zen/v1");
    }

    @Test void registryOwnsPresetCapabilities() {
        var pol = new ProviderPresetRegistry().require(ProviderPresetCode.OPENCODE_ZEN_FREE);
        assertThat(pol.capabilities()).containsExactlyInAnyOrder(ModelCapability.CHAT, ModelCapability.STREAMING,
                ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE);
    }
}
