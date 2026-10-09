package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.infrastructure.ai.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/SSE through the production adapter. Local endpoint exception exists only in this test. */
class ModelProtocolHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private JsonHttpModelClient transport;
    private OpenAiCompatibleModelAdapter adapter;
    private ModelConfiguration config;
    private AtomicReference<JsonNode> request = new AtomicReference<>();
    private AtomicReference<Headers> headers = new AtomicReference<>();
    private AtomicInteger requests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        transport = new JsonHttpModelClient(json, mock(OutboundEndpointPolicy.class),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(2));
        adapter = new OpenAiCompatibleModelAdapter(json, transport);
        config = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "test", ModelProviderType.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort(), "/chat/completions", null,
                "synthetic-model", true, 0, 6000, EnumSet.allOf(ModelCapability.class), OffsetDateTime.now(), OffsetDateTime.now());
    }
    @AfterEach void stop() { transport.close(); server.stop(0); }
    private void reply(int status, String type, String content) {
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            request.set(json.readTree(exchange.getRequestBody())); headers.set(exchange.getRequestHeaders());
            exchange.getResponseHeaders().set("Content-Type", type);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { for (byte b : bytes) { output.write(b); output.flush(); } }
        });
    }
    private ChatCompletionResult complete() {
        return adapter.completeStreamingSyncWithSession(config, "synthetic-key",
                new ChatCompletionCommand("system", "输出 JSON", ChatCompletionCommand.OutputFormat.PROMPT_JSON),
                AiRequestMetadata.of("synthetic-session"), "opencode/1.18.21");
    }
    private ModelTurnResult turn() {
        return adapter.turnWithSession(config, "synthetic-key", new ModelTurnCommand(ModelPurpose.AGENT,
                List.of(new ModelMessage.User("query")), List.of(new ModelToolDefinition("get_task", "query", json.createObjectNode().put("type", "object"))), false),
                AiRequestMetadata.of("synthetic-session"), "opencode/1.18.21");
    }
    @Test void zen_wire_and_content_fragments_without_usage() {
        reply(200, "text/event-stream", ": comment\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"true}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        var result = complete();
        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(result.promptTokens()).isNull();
        assertThat(request.get().path("stream").asBoolean()).isTrue();
        assertThat(request.get().has("response_format")).isFalse();
        assertThat(request.get().has("max_tokens")).isFalse();
        assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer synthetic-key");
        assertThat(headers.get().getFirst("User-Agent")).isEqualTo("opencode/1.18.21");
        assertThat(headers.get().getFirst("x-opencode-session")).isNotBlank();
        assertThat(headers.get().getFirst("x-opencode-client")).isEqualTo("cli");
        assertThat(headers.get().getFirst("x-opencode-project")).isEqualTo("global");
        assertThat(headers.get().getFirst("x-opencode-request")).isNotBlank();
        assertThat(request.get().path("tools").findValuesAsText("name")).containsExactly("bash", "read");
    }
    @Test void tool_arguments_are_assembled_before_return() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"get_task\",\"arguments\":\"{\\\"taskId\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"123\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
        var result = turn();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().arguments().path("taskId").asText()).isEqualTo("123");
        assertThat(request.get().path("stream").asBoolean()).isTrue();
        assertThat(request.get().path("tools").findValuesAsText("name")).containsExactly("get_task", "bash", "read");
    }
    @ParameterizedTest @CsvSource({"401,AI_MODEL_CREDENTIAL_INVALID","403,AI_PROVIDER_REQUEST_REJECTED","429,AI_PROVIDER_QUOTA_EXCEEDED","500,AI_PROVIDER_ERROR","408,AI_MODEL_TIMEOUT","504,AI_MODEL_TIMEOUT"})
    void status_mapping_never_retries_zen(int status, ErrorCode code) {
        reply(status, "application/json", "{\"error\":\"synthetic\"}");
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void free_tier_rejection_is_classified_without_exposing_the_response_body() {
        reply(403, "application/json", "{\"error\":{\"name\":\"FreeTierError\",\"message\":\"synthetic-secret-do-not-echo\"}}");
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
            assertThat(e.getMessage()).contains("FreeTierError").doesNotContain("synthetic-secret");
        });
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void transport_reserved_tool_calls_are_rejected_before_entering_the_runtime() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"reserved-1\",\"function\":{\"name\":\"bash\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
        assertThatThrownBy(this::turn).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }
    @Test void connection_probe_uses_the_same_free_tier_envelope_and_sse_parser() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        var registry = mock(ProviderPresetRegistry.class);
        var policy = new ProviderPresetRegistry.PresetPolicy(ProviderPresetCode.OPENCODE_ZEN_FREE, "test", config.providerType(),
                config.baseUrl(), config.apiPath(), "/models", "synthetic-client", true, config.capabilities(), 0, 256);
        when(registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE)).thenReturn(policy);
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        new HttpOpenCodeZenTransport(json, mock(OutboundEndpointPolicy.class), registry, client, client)
                .validateCredential("synthetic-key", "synthetic-model", AiRequestMetadata.of("probe"));
        assertThat(request.get().path("stream").asBoolean()).isTrue();
        assertThat(request.get().path("tools").findValuesAsText("name")).containsExactly("bash", "read");
        assertThat(headers.get().getFirst("x-opencode-session")).matches("ses_[0-9a-f]{12}[A-Za-z0-9]{14}");
        assertThat(headers.get().getFirst("x-opencode-request")).matches("msg_[0-9a-f]{12}[A-Za-z0-9]{14}");
    }
    @Test void custom_model_streams_do_not_receive_the_zen_envelope() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        var error = new AtomicReference<Exception>();
        adapter.completeStream(config, "synthetic-key", new ChatCompletionCommand("system", "query"), ignored -> {}, ignored -> {}, error::set);
        assertThat(error.get()).isNull();
        assertThat(request.get().has("tools")).isFalse();
        assertThat(headers.get().getFirst("x-opencode-client")).isNull();
    }
    @Test void business_tools_cannot_claim_a_reserved_transport_name() {
        assertThatThrownBy(() -> adapter.turnWithSession(config, "synthetic-key",
                new ModelTurnCommand(ModelPurpose.AGENT, List.of(new ModelMessage.User("query")),
                        List.of(new ModelToolDefinition("bash", "business tool", json.createObjectNode().put("type", "object"))), false),
                AiRequestMetadata.of("collision"), "synthetic-client"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
        assertThat(requests.get()).isZero();
    }
    @Test void incomplete_stream_is_rejected() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }
    @Test void truncated_stream_is_not_a_complete_answer() {
        reply(200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n");
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));
    }
    @Test void non_sse_success_is_rejected() {
        reply(200, "application/json", "{\"choices\":[]}");
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }
    @Test void multiline_sse_data_is_one_event() {
        reply(200, "text/event-stream", "event: message\ndata: {\"choices\":\ndata: [{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        assertThat(complete().content()).isEqualTo("ok");
    }
    @Test void stalled_body_has_total_deadline() {
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
            try { Thread.sleep(2600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertThatThrownBy(this::complete).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT));
    }
    @Test void non_streaming_body_also_has_a_total_deadline() {
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
            try { Thread.sleep(2600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertThatThrownBy(() -> adapter.complete(config,"synthetic-key",new ChatCompletionCommand("synthetic","probe")))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_TIMEOUT));
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void interrupt_cancels_pending_transport() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        server.createContext("/chat/completions", exchange -> {
            accepted.countDown(); try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } exchange.close();
        });
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> { try { complete(); } catch (Throwable e) { error.set(e); } });
        caller.start(); assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(1000);
        assertThat(caller.isAlive()).isFalse(); assertThat(error.get()).isInstanceOf(BusinessException.class);
    }
}
