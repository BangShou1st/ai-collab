package com.shitulelv.aicollab.infrastructure.ai;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatModelGatewayTest {

    @Test
    void jsonModeCapabilityCanOmitResponseFormat() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            capturedBody.set(new BufferedReader(new InputStreamReader(
                    exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().reduce("", (a, b) -> a + b));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-key", "fake-model", Duration.ofSeconds(5),
                    Duration.ofSeconds(10), 0.2, 1000, false);

            new OpenAiCompatibleChatModelGateway(properties, false).complete(
                    new ChatCompletionCommand(null, "sys", "usr",
                            ChatCompletionCommand.OutputFormat.JSON_OBJECT));

            assertThat(capturedBody.get()).doesNotContain("response_format");
        } finally {
            server.stop(0);
        }
    }

    // ── S1 RED: JSON_OBJECT command must send response_format.type=json_object ──

    @Test
    void jsonObjectCommandSendsResponseFormat() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().reduce("", (a, b) -> a + b);
            capturedBody.set(body);
            String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],\"model\":\"m\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-key", "fake-model", Duration.ofSeconds(5),
                    Duration.ofSeconds(10), 0.7, 4096, true);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            ChatCompletionCommand cmd = new ChatCompletionCommand("sys", "usr",
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT);
            ChatCompletionResult result = gateway.complete(cmd);

            assertThat(result.content()).isEqualTo("{\"ok\":true}");
            assertThat(capturedBody.get()).contains("\"response_format\"");
            assertThat(capturedBody.get()).contains("\"type\":\"json_object\"");
            assertThat(capturedBody.get()).contains("\"stream\":false");
            // Authorization must NOT leak into test output
            assertThat(capturedBody.get()).doesNotContain("test-key");
        } finally {
            server.stop(0);
        }
    }

    // ── S1 RED: TEXT command must NOT send response_format ──

    @Test
    void textCommandOmitsResponseFormat() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().reduce("", (a, b) -> a + b);
            capturedBody.set(body);
            String response = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}],\"model\":\"m\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-key", "fake-model", Duration.ofSeconds(5),
                    Duration.ofSeconds(10), 0.7, 4096, true);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            ChatCompletionCommand cmd = new ChatCompletionCommand("sys", "usr");
            ChatCompletionResult result = gateway.complete(cmd);

            assertThat(result.content()).isEqualTo("hello");
            assertThat(capturedBody.get()).doesNotContain("response_format");
        } finally {
            server.stop(0);
        }
    }

    // ── S5 RED: finish_reason=length must map to OUTPUT_TRUNCATED ──

    @Test
    void finishReasonLengthMapsToOutputTruncated() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            String response = "{\"choices\":[{\"message\":{\"content\":\"partial json\"},\"finish_reason\":\"length\"}],\"model\":\"m\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-key", "fake-model", Duration.ofSeconds(5),
                    Duration.ofSeconds(10), 0.7, 4096, true);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            ChatCompletionCommand cmd = new ChatCompletionCommand("sys", "usr",
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT);
            // Should throw with OUTPUT_TRUNCATED error code, not return truncated content
            assertThatThrownBy(() -> gateway.complete(cmd))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));
        } finally {
            server.stop(0);
        }
    }

    // ── S5 RED: finish_reason=stop is normal ──

    @Test
    void finishReasonStopReturnsContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            String response = "{\"choices\":[{\"message\":{\"content\":\"complete\"},\"finish_reason\":\"stop\"}],\"model\":\"m\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-key", "fake-model", Duration.ofSeconds(5),
                    Duration.ofSeconds(10), 0.7, 4096, true);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            ChatCompletionCommand cmd = new ChatCompletionCommand("sys", "usr");
            ChatCompletionResult result = gateway.complete(cmd);
            assertThat(result.content()).isEqualTo("complete");
        } finally {
            server.stop(0);
        }
    }

    // ── existing test preserved ──

    @Test
    void planningModeDoesNotRetryOneProviderRequestInsideOneAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(504, -1);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ChatModelProperties(true, "fake",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/chat",
                    "test-placeholder", "fake-model", Duration.ofSeconds(2),
                    Duration.ofSeconds(2), 0, 100, true);
            var gateway = new OpenAiCompatibleChatModelGateway(properties, false);

            assertThatThrownBy(() -> gateway.complete(new ChatCompletionCommand("system", "user")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT));
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
