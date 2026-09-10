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
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZenKnowledgeStreamContractTest {    private final ObjectMapper mapper = new ObjectMapper();

    private ModelConfiguration zenConfig() {
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://opencode.ai/zen/v1", "/chat/completions",
                null, "mimo-v2.5-free", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ChatCompletionCommand textCommand() {
        return new ChatCompletionCommand(UUID.randomUUID(), "answer directly", "what is the status?",
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), UUID.randomUUID());
    }

    private UserAiProvider zenProvider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://opencode.ai/zen/v1", "/chat/completions", "enc",
                "mimo-v2.5-free", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING),
                false, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }

    @Test void zenTextWireStreamsWithMinimalBodyAndHeaders() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        final JsonNode[] captured = new JsonNode[1];
        final Map[] headerRef = new Map[1];
        doAnswer(inv -> {
            headerRef[0] = inv.getArgument(1);
            captured[0] = inv.getArgument(2);
            BiConsumer<String, JsonNode> cb = inv.getArgument(3);
            ObjectNode chunk = mapper.createObjectNode();
            chunk.putArray("choices").addObject().putObject("delta").put("content", "answer");
            cb.accept("message", chunk);
            ObjectNode done = mapper.createObjectNode();
            done.putArray("choices").addObject().put("finish_reason", "stop");
            cb.accept("message", done);
            return null;
        }).when(http).stream(anyString(), anyMap(), any(JsonNode.class), any(BiConsumer.class));
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(mapper, http);
        ChatCompletionResult result = adapter.completeStreamingSyncWithSession(
                zenConfig(), "k", textCommand(), AiRequestMetadata.of("corr-1"), "opencode/1.18.21");
        assertThat(result.content()).isEqualTo("answer");
        JsonNode body = captured[0];
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("mimo-v2.5-free");
        assertThat(body.has("messages")).isTrue();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.has("stream_options")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, String> headers = headerRef[0];
        assertThat(headers.get("Authorization")).isEqualTo("Bearer k");
        assertThat(headers.get("User-Agent")).isEqualTo("opencode/1.18.21");
        assertThat(headers.get(OpenCodeZenTransport.SESSION_HEADER)).isEqualTo("corr-1");
    }

    @Test void zenExecutionCompleteUsesStreamingWireForTextAndJson() {
        OpenAiCompatibleModelAdapter adapter = mock(OpenAiCompatibleModelAdapter.class);
        ChatCompletionResult canned = mock(ChatCompletionResult.class);
        when(adapter.completeStreamingSyncWithSession(
                any(ModelConfiguration.class), anyString(), any(ChatCompletionCommand.class),
                any(AiRequestMetadata.class), anyString())).thenReturn(canned);
        var exec = new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy(), adapter);
        exec.complete(zenProvider(), "k", textCommand(), AiRequestMetadata.of("corr-1"));
        ChatCompletionCommand json = new ChatCompletionCommand(UUID.randomUUID(), "sys", "u",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT, ModelPurpose.PLANNING, null, List.of(), UUID.randomUUID());
        exec.complete(zenProvider(), "k", json, AiRequestMetadata.of("corr-2"));
        verify(adapter, never()).completeWithSession(any(), anyString(), any(), any(), anyString());
        verify(adapter, times(2)).completeStreamingSyncWithSession(
                any(ModelConfiguration.class), eq("k"), any(ChatCompletionCommand.class),
                any(AiRequestMetadata.class), eq("opencode/1.18.21"));
    }
}
