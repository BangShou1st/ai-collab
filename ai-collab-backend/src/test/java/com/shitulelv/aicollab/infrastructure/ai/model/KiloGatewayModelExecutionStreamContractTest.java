package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式完成契约：用真实共享库客户端（v0.2.2）+ 本地 SSE 服务器验证，
 * 只有观察到 {@code data: [DONE]} 才算成功；截断 / 错误帧 / 非法帧一律失败，
 * 且成功与失败回调互斥，未完成流绝不调用 onDone。
 */
class KiloGatewayModelExecutionStreamContractTest {
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private KiloGatewayModelExecution execution;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        KiloGatewayProperties properties = new KiloGatewayProperties();
        // 共享库只允许 HTTPS，回环 HTTP 仅用于本地测试。
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel(FREE_MODEL);
        execution = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper,
                new KiloGatewayClientTransport(new KiloGatewayClient(properties)));
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private UserAiProvider provider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://evil.example", "/evil", null, FREE_MODEL,
                true, 0.2, 4000, EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING), false,
                OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");
    }

    private ChatCompletionCommand command() {
        return new ChatCompletionCommand("sys", "hi");
    }

    /** 记录回调次数，断言成功与失败互斥。 */
    private static final class Signals {
        final List<String> tokens = new ArrayList<>();
        final AtomicInteger done = new AtomicInteger();
        final List<Exception> errors = new ArrayList<>();
        void assertSucceededOnce() {
            assertThat(done.get()).isEqualTo(1);
            assertThat(errors).isEmpty();
        }
        void assertFailedOnce(ErrorCode expected) {
            assertThat(done.get()).isZero();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) errors.get(0)).getErrorCode()).isEqualTo(expected);
        }
    }

    private Signals stream(String sseBody) {
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().close();
            respond(exchange, 200, sseBody);
        });
        server.start();
        Signals signals = new Signals();
        execution.completeStream(provider(), command(), signals.tokens::add,
                result -> signals.done.incrementAndGet(), signals.errors::add);
        return signals;
    }

    @Test void doneSentinelCompletesTheStream() {
        Signals signals = stream("data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"y\"}}]}\n\n"
                + "data: [DONE]\n\n");
        assertThat(signals.tokens).containsExactly("he", "y");
        signals.assertSucceededOnce();
    }

    @Test void partialContentThenEofNeverCallsOnDone() {
        Signals signals = stream("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        assertThat(signals.tokens).containsExactly("partial");
        signals.assertFailedOnce(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
    }

    @Test void completeLookingOutputWithoutDoneSignalIsStillAFailure() {
        Signals signals = stream("data: {\"choices\":[{\"delta\":{\"content\":\"whole answer\"},"
                + "\"finish_reason\":\"stop\"}]}\n\n");
        assertThat(signals.tokens).containsExactly("whole answer");
        signals.assertFailedOnce(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
    }

    @Test void finishReasonLengthIsReportedAsTruncatedOutput() {
        Signals signals = stream("data: {\"choices\":[{\"delta\":{\"content\":\"cut\"},"
                + "\"finish_reason\":\"length\"}]}\n\n"
                + "data: [DONE]\n\n");
        signals.assertFailedOnce(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
    }

    @Test void midStreamErrorFrameFailsWithoutReplayingOutput() {
        Signals signals = stream("data: {\"choices\":[{\"delta\":{\"content\":\"O\"}}]}\n\n"
                + "data: {\"error\":{\"message\":\"upstream exploded\",\"metadata\":{\"provider_name\":\"Nvidia\"}}}\n\n"
                + "data: [DONE]\n\n");
        assertThat(signals.tokens).containsExactly("O");
        signals.assertFailedOnce(ErrorCode.AI_PROVIDER_ERROR);
    }

    @Test void undecodableFrameFailsTheStream() {
        Signals signals = stream("data: {not-json\n\n");
        signals.assertFailedOnce(ErrorCode.AI_PROVIDER_ERROR);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
