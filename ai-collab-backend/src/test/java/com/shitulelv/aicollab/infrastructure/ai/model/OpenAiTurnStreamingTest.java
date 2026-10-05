package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class OpenAiTurnStreamingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ModelConfiguration config() {
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "t",
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.com", "/v1/chat/completions",
                null, "test-model", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS),
                OffsetDateTime.now(), OffsetDateTime.now());
    }
    private ModelTurnCommand cmd() {
        ModelToolDefinition tool = new ModelToolDefinition("list_tasks", "list", mapper.createObjectNode());
        return new ModelTurnCommand(ModelPurpose.AGENT, List.of(new ModelMessage.User("hi")), List.of(tool), false);
    }
    private ObjectNode deltaChunk(String content, String finish, ObjectNode... toolDeltas) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        if (content != null) delta.put("content", content);
        if (toolDeltas != null && toolDeltas.length > 0) {
            ArrayNode arr = delta.putArray("tool_calls");
            for (ObjectNode td : toolDeltas) arr.add(td);
        }
        if (finish != null) choice.put("finish_reason", finish);
        return root;
    }
    private ObjectNode toolDelta(int index, String id, String name, String args) {
        ObjectNode n = mapper.createObjectNode();
        n.put("index", index);
        if (id != null) n.put("id", id);
        ObjectNode fn = n.putObject("function");
        if (name != null) fn.put("name", name);
        if (args != null) fn.put("arguments", args);
        return n;
    }
    @SuppressWarnings("unchecked")
    private JsonHttpModelClient streamingHttp(List<ObjectNode> chunks) {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        org.mockito.Mockito.when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR));
        doAnswer(inv -> { BiConsumer<String, JsonNode> cb = inv.getArgument(3); for (ObjectNode c : chunks) cb.accept("message", c); return null; }).when(http).stream(anyString(), anyMap(), any(JsonNode.class), any(BiConsumer.class));
        return http;
    }
    private ModelTurnResult streamingTurn(List<ObjectNode> chunks) {
        return new OpenAiCompatibleModelAdapter(mapper,streamingHttp(chunks)).turnWithSession(config(),"key",cmd(),AiRequestMetadata.fresh(),"opencode/1.18.21");
    }
    @Test void aggregatesFragmentedArgumentsAcrossChunks() {
        List<ObjectNode> chunks = List.of(deltaChunk(null, null, toolDelta(0, "call_1", "list_tasks", "{\"status\":"), toolDelta(0, null, null, "\"OPEN\"}")), deltaChunk(null, "tool_calls"));
        ModelTurnResult r = streamingTurn(chunks);
        assertThat(r.toolCalls()).hasSize(1);
        assertThat(r.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(r.toolCalls().get(0).arguments().path("status").asText()).isEqualTo("OPEN");
        assertThat(r.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }
    @Test void aggregatesMultipleToolCalls() {
        List<ObjectNode> chunks = List.of(deltaChunk(null, null, toolDelta(0, "call_1", "list_tasks", "{}")), deltaChunk(null, null, toolDelta(1, "call_2", "list_milestones", "{}")), deltaChunk(null, "tool_calls"));
        ModelTurnResult r = streamingTurn(chunks);
        assertThat(r.toolCalls()).hasSize(2);
    }
    @Test void textPlusToolCalls() {
        List<ObjectNode> chunks = List.of(deltaChunk("hi-", null), deltaChunk("there", null, toolDelta(0, "call_1", "list_tasks", "{}")), deltaChunk(null, "tool_calls"));
        ModelTurnResult r = streamingTurn(chunks);
        assertThat(r.content()).isEqualTo("hi-there");
    }
    @Test void malformedArgumentsThrows() {
        List<ObjectNode> chunks = List.of(deltaChunk(null, null, toolDelta(0, "call_1", "list_tasks", "not-json")), deltaChunk(null, "tool_calls"));
        assertThatThrownBy(() -> streamingTurn(chunks)).isInstanceOf(BusinessException.class);
    }
    @Test void completesWithoutWait() {
        List<ObjectNode> chunks = List.of(deltaChunk("done", "stop"));
        long s = System.nanoTime();
        ModelTurnResult r = streamingTurn(chunks);
        assertThat(r.content()).isEqualTo("done");
        assertThat((System.nanoTime() - s) / 1000000L).isLessThan(5000L);
    }

    /** 带用量收尾块的 chunk（流式 usage 在收尾块到达）。 */
    private ObjectNode usageChunk(String content, String finish, int prompt, int completion) {
        ObjectNode root = deltaChunk(content, finish);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        return root;
    }

    /** 截断出口：提供商已上报的 usage 随异常携带，不得替换成估算/未知。 */
    @Test void truncatedStreamCarriesProviderUsageIntoException() {
        List<ObjectNode> chunks = List.of(
                deltaChunk("已生成的部分", null),
                usageChunk(null, "length", 1234, 567));
        assertThatThrownBy(() -> streamingTurn(chunks))
                .isInstanceOfSatisfying(ProviderResponseFailure.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                    assertThat(failure.carriedPromptTokens()).isEqualTo(1234);
                    assertThat(failure.carriedCompletionTokens()).isEqualTo(567);
                });
    }

    /** 空结果出口：已收到的 usage 同样随异常携带。 */
    @Test void emptyStreamCarriesUsageIntoException() {
        List<ObjectNode> chunks = List.of(usageChunk(null, null, 900, 0));
        assertThatThrownBy(() -> streamingTurn(chunks))
                .isInstanceOfSatisfying(ProviderResponseFailure.class, failure -> {
                    assertThat(failure.carriedPromptTokens()).isEqualTo(900);
                    assertThat(failure.carriedCompletionTokens()).isEqualTo(0);
                });
    }

    /** 非流式 complete 截断：usage 先于截断判定读取，随异常携带。 */
    @Test void nonStreamingTruncationCarriesUsage() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode choice = response.putArray("choices").addObject();
        choice.putObject("message").putObject("content");
        choice.put("finish_reason", "length");
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", 800);
        usage.put("completion_tokens", 1200);
        org.mockito.Mockito.when(http.post(anyString(), anyMap(), any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(response);
        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(mapper, http).completeWithSession(
                config(), "key", org.mockito.Mockito.mock(ChatCompletionCommand.class),
                AiRequestMetadata.fresh(), "opencode/1.18.21"))
                .isInstanceOfSatisfying(ProviderResponseFailure.class, failure -> {
                    assertThat(failure.carriedPromptTokens()).isEqualTo(800);
                    assertThat(failure.carriedCompletionTokens()).isEqualTo(1200);
                });
    }
}
