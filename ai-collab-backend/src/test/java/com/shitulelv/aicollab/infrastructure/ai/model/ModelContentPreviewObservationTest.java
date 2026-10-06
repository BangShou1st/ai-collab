package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelContentPreview;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 正文流式观察通道：适配器在 Zen 流式聚合路径上把累计正文推给观察者，
 * 聚合结果本身不受影响；观察者失败不能让模型调用失败。
 */
class ModelContentPreviewObservationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ModelConfiguration config() {
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "t",
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.com", "/v1/chat/completions",
                null, "test-model", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ModelTurnCommand cmd() {
        return new ModelTurnCommand(ModelPurpose.AGENT, List.of(new ModelMessage.User("hi")), List.of(), false);
    }

    private ObjectNode deltaChunk(String content, String finish) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        if (content != null) delta.put("content", content);
        if (finish != null) choice.put("finish_reason", finish);
        return root;
    }

    private ObjectNode toolCallChunk(String id, String name, String args) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        ObjectNode tool = delta.putArray("tool_calls").addObject();
        tool.put("index", 0);
        tool.put("id", id);
        tool.putObject("function").put("name", name).put("arguments", args);
        return root;
    }

    private JsonHttpModelClient streamingHttp(List<ObjectNode> chunks) {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        doAnswer(inv -> {
            BiConsumer<String, ObjectNode> cb = inv.getArgument(3);
            for (ObjectNode c : chunks) cb.accept("message", c);
            return null;
        }).when(http).stream(anyString(), anyMap(), any(ObjectNode.class), any(BiConsumer.class));
        return http;
    }

    private ModelTurnResult streamingTurn(List<ObjectNode> chunks) {
        return new OpenAiCompatibleModelAdapter(mapper, streamingHttp(chunks))
                .turnWithSession(config(), "key", cmd(), AiRequestMetadata.fresh(), "opencode/1.18.21");
    }

    @AfterEach
    void clearObserver() {
        ModelContentPreview.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushesFromHttpClientReaderThreadStillReachTheCapturedObserver() throws Exception {
        // 真实链路里 JsonHttpModelClient 的读流回调在 model-stream 池线程执行,
        // ThreadLocal 不可见——必须在发起线程 capture,回调线程显式推送
        List<ObjectNode> chunks = List.of(
                deltaChunk("第一段。", null),
                deltaChunk("第二段。", null),
                deltaChunk("第三段。", "stop"));
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        doAnswer(inv -> {
            BiConsumer<String, ObjectNode> cb = inv.getArgument(3);
            Thread reader = new Thread(() -> {
                for (ObjectNode c : chunks) cb.accept("message", c);
            }, "probe-model-stream");
            reader.start();
            reader.join(5000);
            return null;
        }).when(http).stream(anyString(), anyMap(), any(ObjectNode.class), any(BiConsumer.class));
        List<String> seen = new ArrayList<>();
        List<Boolean> finals = new ArrayList<>();
        ModelContentPreview.Observer observer = (text, finalFrame) -> {
            seen.add(text);
            finals.add(finalFrame);
        };
        ModelContentPreview.activate(observer);
        ModelContentPreview.Observer captured = ModelContentPreview.capture();
        try {
            ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                    .turnWithSession(config(), "key", cmd(), AiRequestMetadata.fresh(), "opencode/1.18.31");
            assertThat(result.content()).isEqualTo("第一段。第二段。第三段。");
        } finally {
            ModelContentPreview.clear();
        }
        assertThat(seen).containsExactly(
                "第一段。", "第一段。第二段。", "第一段。第二段。第三段。", "第一段。第二段。第三段。");
        assertThat(finals).containsExactly(false, false, false, true);
        org.assertj.core.api.Assertions.assertThat(captured).isSameAs(observer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingTurnPublishesCumulativeContentAndFinishWithoutChangingResult() {
        List<ObjectNode> chunks = List.of(
                deltaChunk("我先", null),
                deltaChunk("查看任务，", null),
                deltaChunk("然后核对里程碑。", "stop"));
        List<String> seen = new ArrayList<>();
        List<Boolean> finals = new ArrayList<>();
        ModelContentPreview.activate((text, finalFrame) -> {
            seen.add(text);
            finals.add(finalFrame);
        });

        ModelTurnResult result = streamingTurn(chunks);

        assertThat(result.content()).isEqualTo("我先查看任务，然后核对里程碑。");
        // 三个 push 帧之后，流结束的累计正文以 final 帧再次到达（finish 不参与节流合并）
        assertThat(seen).containsExactly(
                "我先", "我先查看任务，", "我先查看任务，然后核对里程碑。", "我先查看任务，然后核对里程碑。");
        assertThat(finals).containsExactly(false, false, false, true);
    }

    @Test
    void observerFailureDoesNotFailTheModelCall() {
        List<ObjectNode> chunks = List.of(deltaChunk("正文", null), deltaChunk(null, "stop"));
        ModelContentPreview.activate((text, finalFrame) -> {
            throw new IllegalStateException("展示侧失败");
        });

        ModelTurnResult result = streamingTurn(chunks);

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
    }

    @Test
    void pushWithoutActiveObserverIsANoOp() {
        List<ObjectNode> chunks = List.of(deltaChunk("无观察者", "stop"));
        ModelTurnResult result = streamingTurn(chunks);
        assertThat(result.content()).isEqualTo("无观察者");
    }

    @Test
    void toolCallOnlyTurnFinishesWithoutContent() {
        List<ObjectNode> chunks = List.of(
                toolCallChunk("call_1", "list_tasks", "{}"),
                deltaChunk(null, "tool_calls"));
        List<String> seen = new ArrayList<>();
        ModelContentPreview.activate((text, finalFrame) -> seen.add(text));

        ModelTurnResult result = streamingTurn(chunks);

        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).hasSize(1);
        // 纯工具轮无正文：不推送任何内容帧
        assertThat(seen).isEmpty();
    }

    @Test
    void modelNeverEmittingTokensStillAggregatesEmptyContentFailure() {
        // 空流：无正文无工具——原有"既无文本也无工具调用"协议错误保持，观察通道不改变该语义
        List<ObjectNode> chunks = List.of();
        ModelContentPreview.activate((text, finalFrame) -> { throw new AssertionError("不应有推送"); });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streamingTurn(chunks))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
    }
}
