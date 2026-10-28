package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Gemini 原生 SSE 流契约测试：
 * 验证正常完成（finishReason STOP + 正常 EOF 关闭，无 [DONE]）、
 * 截断流（完成信号前异常 EOF）、空流、MAX_TOKENS 截断、安全策略、
 * 错误帧、多行 SSE 数据、网络异常与超时的正确分类。
 */
class GeminiStreamContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private JsonHttpModelClient transport;
    private GeminiModelAdapter adapter;
    private ModelConfiguration config;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        transport = new JsonHttpModelClient(json, mock(OutboundEndpointPolicy.class),
                com.shitulelv.aicollab.common.net.OutboundHttpClients.builder().build(), Duration.ofSeconds(2));
        adapter = new GeminiModelAdapter(json, transport);
        config = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "test", ModelProviderType.GEMINI,
                "http://127.0.0.1:" + server.getAddress().getPort(), "/v1beta/models/{model}:generateContent", null,
                "gemini-test", true, 0.2, 4000, EnumSet.allOf(ModelCapability.class), OffsetDateTime.now(), OffsetDateTime.now());
    }

    @AfterEach
    void stop() {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
    }

    private ChatCompletionCommand textCommand() {
        return new ChatCompletionCommand(null, "system prompt", "Count 1 2 3.",
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);
    }

    private static final class StreamRecorder {
        final List<String> tokens = new ArrayList<>();
        final AtomicReference<ChatCompletionResult> doneResult = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicInteger doneCount = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);

        void await() throws InterruptedException {
            latch.await(5, TimeUnit.SECONDS);
        }
    }

    private StreamRecorder runStream() throws InterruptedException {
        StreamRecorder recorder = new StreamRecorder();
        adapter.completeStream(config, "test-api-key", textCommand(),
                recorder.tokens::add,
                res -> {
                    recorder.doneResult.set(res);
                    recorder.doneCount.incrementAndGet();
                    recorder.latch.countDown();
                },
                err -> {
                    recorder.error.set(err);
                    recorder.latch.countDown();
                });
        recorder.await();
        return recorder;
    }

    private void replySse(String body) {
        server.createContext("/v1beta/models/gemini-test:streamGenerateContent", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        });
    }

    @Test
    void normalCompletionWithFinishReasonStopAndEof() throws Exception {
        // Gemini 正常流：多段 candidate 输出，最后一段携带 finishReason: "STOP" 和 usageMetadata，以 EOF 正常关闭，不发 [DONE]
        String sse = """
                data: {"candidates":[{"content":{"parts":[{"text":"1, "}]},"index":0}]}
                
                data: {"candidates":[{"content":{"parts":[{"text":"2, 3."}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.error.get()).isNull();
        assertThat(recorder.doneCount.get()).isEqualTo(1);
        assertThat(recorder.tokens).containsExactly("1, ", "2, 3.");
        assertThat(recorder.doneResult.get().content()).isEqualTo("1, 2, 3.");
        assertThat(recorder.doneResult.get().promptTokens()).isEqualTo(10);
        assertThat(recorder.doneResult.get().completionTokens()).isEqualTo(5);
    }

    @Test
    void abnormalEofBeforeFinishReasonFailsAsTruncated() throws Exception {
        // 在模型给出 finishReason 之前连接意外中断（EOF）：不得误判为成功，必须保留失败语义
        String sse = """
                data: {"candidates":[{"content":{"parts":[{"text":"partial answer"}]},"index":0}]}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        assertThat(be.getMessage()).contains("TRUNCATED_BEFORE_FINISH");
    }

    @Test
    void emptyStreamFails() throws Exception {
        // 空流：HTTP 200 text/event-stream 但没有任何数据即 EOF
        replySse("");

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        assertThat(be.getMessage()).contains("EMPTY_STREAM");
    }

    @Test
    void finishReasonMaxTokensThrowsOutputTruncated() throws Exception {
        String sse = """
                data: {"candidates":[{"content":{"parts":[{"text":"cut off"}]},"finishReason":"MAX_TOKENS","index":0}]}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
    }

    @Test
    void finishReasonSafetyThrowsRequestRejected() throws Exception {
        String sse = """
                data: {"candidates":[{"content":{"parts":[{"text":"unsafe content"}]},"finishReason":"SAFETY","index":0}]}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
    }

    @Test
    void sseErrorEnvelopeFails() throws Exception {
        String sse = """
                data: {"error":{"code":500,"message":"Internal model error"}}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        assertThat(be.getMessage()).contains("ERROR_ENVELOPE");
    }

    @Test
    void multilineAndChunkedSseDataParsedProperly() throws Exception {
        // 多行 data 属于同一 SSE 事件
        String sse = """
                : ping comment
                
                data: {"candidates":[{"content":{"parts":
                data: [{"text":"multiline chunk"}]},"finishReason":"STOP","index":0}]}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.error.get()).isNull();
        assertThat(recorder.doneCount.get()).isEqualTo(1);
        assertThat(recorder.tokens).containsExactly("multiline chunk");
        assertThat(recorder.doneResult.get().content()).isEqualTo("multiline chunk");
    }

    @Test
    void unexpectedToolCallInChatCompletionStreamIgnoredOrFiltered() throws Exception {
        // 当模型在普通文本流中意外返回 functionCall 时，文本部分正常接收，且不致崩溃
        String sse = """
                data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"query","args":{}}},{"text":"hello"}]},"finishReason":"STOP","index":0}]}
                
                """;
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.error.get()).isNull();
        assertThat(recorder.doneResult.get().content()).isEqualTo("hello");
    }

    @Test
    void networkInterruptionPropagatedAsProviderError() throws Exception {
        server.createContext("/v1beta/models/gemini-test:streamGenerateContent", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            exchange.getResponseBody().write("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"part1\"}]}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            // 异常中断：直接关闭底层连接而不正常结束 chunked 流
            exchange.close();
        });

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        // 网络中断或在完成前 EOF 均应保留失败语义（AI_PROVIDER_ERROR 或 AI_PROVIDER_INVALID_RESPONSE）
        assertThat(be.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR || be.getErrorCode() == ErrorCode.AI_PROVIDER_INVALID_RESPONSE).isTrue();
    }

    @Test
    void timeoutPropagatedAsModelTimeout() throws Exception {
        server.createContext("/v1beta/models/gemini-test:streamGenerateContent", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2600); // 超过 2 秒传输超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT);
    }

    @Test
    void finishReasonBlocklistThrowsRequestRejected() throws Exception {
        // 当模型因 BLOCKLIST、PROHIBITED_CONTENT 或 SPII 终止时，必须抛出 AI_PROVIDER_REQUEST_REJECTED，绝不能被误判为成功
        String sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"敏感词开头...\"}]},\"finishReason\":\"BLOCKLIST\",\"index\":0}]}\n\n";
        replySse(sse);

        StreamRecorder recorder = runStream();

        assertThat(recorder.doneCount.get()).isZero();
        assertThat(recorder.error.get()).isNotNull().isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) recorder.error.get();
        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
    }
}
