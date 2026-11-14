package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class ModelBridgeTestSupport {
    private ModelBridgeTestSupport() {
    }

    static ModelBridgeClient client(FakeChatAdapter adapter) {
        return new ModelBridgeClient(new AdapterRegistry().register(adapter));
    }

    static ModelConfiguration config(
            ModelProviderType type, String baseUrl, String apiPath, String modelName) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), type.name(),
                type, baseUrl, apiPath, "encrypted", modelName, true, 0.2, 512,
                EnumSet.allOf(ModelCapability.class), now, now);
    }

    static ModelTurnCommand turnCommand(
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools,
            boolean required) {
        return new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                messages, tools, required, UUID.randomUUID());
    }

    static ModelToolDefinition tool(String name) {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("type", "object");
        schema.putObject("properties").putObject("status").put("type", "string");
        return new ModelToolDefinition(name, "tool " + name, schema);
    }

    static ChatResponse textResponse(String content, FinishReason finish, String model) {
        return new ChatResponse(content, null, List.of(), finish,
                new TokenUsage(10, 4), "test", model, 3, Map.of());
    }

    static ChatResponse toolResponse(
            String content, List<ToolCall> calls, FinishReason finish, String model) {
        return new ChatResponse(content, null, calls, finish,
                new TokenUsage(20, 8), "test", model, 4, Map.of());
    }

    static StreamChunk content(String delta, String model) {
        return StreamChunk.content(delta, model, "test");
    }

    static StreamChunk done(FinishReason finish, int input, int output, String model) {
        return StreamChunk.done(finish, new TokenUsage(input, output), model, "test");
    }

    static class FakeChatAdapter implements ChatProtocolAdapter {
        private final ProtocolId protocol;
        ChatResponse response;
        RuntimeException failure;
        List<StreamChunk> chunks = List.of();
        RuntimeException streamFailure;
        ChatRequest lastRequest;
        ProviderConfig lastProvider;
        FakeChatAdapter(ProtocolId protocol) {
            this.protocol = protocol;
        }

        @Override
        public String name() {
            return "test-" + protocol.value();
        }

        @Override
        public Set<ProtocolId> protocols() {
            return Set.of(protocol);
        }

        @Override
        public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
            capture(provider, request);
            if (failure != null) {
                throw failure;
            }
            if (response == null) {
                throw new IllegalStateException("fake response is not configured");
            }
            return response;
        }

        @Override
        public void completeStream(
                ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer) {
            capture(provider, request);
            if (streamFailure != null) {
                throw streamFailure;
            }
            chunks.forEach(consumer);
        }

        @Override
        public StreamHandle stream(
                ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer) {
            return StreamHandles.start(Runnable::run,
                    handle -> completeStream(provider, request, consumer));
        }

        private void capture(ProviderConfig provider, ChatRequest request) {
            lastProvider = provider;
            lastRequest = request;
        }
    }

    static ModelBridgeException bridgeFailure(
            io.github.bangshou1st.modelbridge.core.security.ErrorCategory category,
            int status, TokenUsage usage) {
        return new ModelBridgeException(category, "test failure", "test", status, null,
                usage, null);
    }

    static List<ModelMessage> messages(ModelMessage... values) {
        return new ArrayList<>(List.of(values));
    }
}
