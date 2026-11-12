package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlamaCppRerankAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOneRequestAndMapsScoresByIndex() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"results":[
                      {"index":2,"relevance_score":0.9},
                      {"index":0,"relevance_score":0.1},
                      {"index":1,"relevance_score":0.5}
                    ]}
                    """);
        });

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "capital of France", List.of(
                        document("a", "France", "Paris is the capital."),
                        document("b", "Fruit", "Banana."),
                        document("c", "Geography", "France is in Europe.")),
                2, null);

        assertThat(result.success()).isTrue();
        assertThat(result.scores()).extracting(RerankScore::id).containsExactly("a", "b", "c");
        assertThat(result.scores()).extracting(RerankScore::score).containsExactly(0.1, 0.5, 0.9);
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.topK()).isEqualTo(2);
        assertThat(result.modelLatencyMs()).isNotNull();
        assertThat(result.totalMs()).isGreaterThanOrEqualTo(0);
        assertThat(calls).hasValue(1);

        JsonNode payload = objectMapper.readTree(body.get());
        assertThat(payload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("query", "documents", "top_n");
        assertThat(payload.path("documents").get(0).asText())
                .isEqualTo("France\nParis is the capital.");
        assertThat(payload.path("top_n").asInt()).isEqualTo(3);
        assertThat(body.get()).doesNotContain("api_key", "projectId", "userId", "modelPath");
    }

    @Test
    void rejectsMissingResult() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"results":[{"index":0,"relevance_score":0.1}]}
                """));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x"), document("b", "t", "y")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INVALID_RESPONSE");
        assertThat(result.reason()).isEqualTo("results_count_mismatch");
    }

    @Test
    void rejectsOutOfRangeIndex() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"results":[{"index":0,"relevance_score":0.1},{"index":2,"relevance_score":0.2}]}
                """));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x"), document("b", "t", "y")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("index_out_of_range");
    }

    @Test
    void rejectsDuplicateIndex() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"results":[{"index":0,"relevance_score":0.1},{"index":0,"relevance_score":0.2}]}
                """));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x"), document("b", "t", "y")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("index_duplicate");
    }

    @Test
    void rejectsNonFiniteScore() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"results":[{"index":0,"relevance_score":1e999},{"index":1,"relevance_score":0.2}]}
                """));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x"), document("b", "t", "y")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void mapsLogitScoresOnce() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"results":[{"index":0,"relevance_score":0},{"index":1,"relevance_score":2}]}
                """));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "logit"),
                "q", List.of(document("a", "t", "x"), document("b", "t", "y")), null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.scores().get(0).score()).isEqualTo(0.5);
        assertThat(result.scores().get(1).score()).isCloseTo(0.880797, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void timesOutWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"results\":[]}");
        });

        RerankCallResult result = adapter(100).rerank(provider("NONE", null), settings(100, "sigmoid"),
                "q", List.of(document("a", "t", "x")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("TIMEOUT");
        assertThat(calls).hasValue(1);
    }

    @Test
    void classifies5xx() throws Exception {
        start(exchange -> respond(exchange, 503, "{}"));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("HTTP_ERROR");
        assertThat(result.reason()).isEqualTo("http_5xx");
    }

    @Test
    void rejectsInvalidJson() throws Exception {
        start(exchange -> respond(exchange, 200, "not-json"));

        RerankCallResult result = adapter(3000).rerank(provider("NONE", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("invalid_json");
    }

    @Test
    void failsClosedWhenCredentialMissing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"results\":[]}");
        });

        RerankCallResult result = adapter(3000).rerank(provider("BEARER", null), settings(3000, "sigmoid"),
                "q", List.of(document("a", "t", "x")), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("credential_unavailable");
        assertThat(calls).hasValue(0);
    }

    @Test
    void requestsAllCandidatesWhenStubTruncatesByTopN() throws Exception {
        for (int size : new int[]{9, 10, 20, 50}) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Integer> requestedTopN = new AtomicReference<>();
            start(exchange -> {
                calls.incrementAndGet();
                JsonNode request = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
                int topN = request.path("top_n").asInt();
                requestedTopN.set(topN);
                StringBuilder results = new StringBuilder("{\"results\":[");
                for (int index = 0; index < topN; index++) {
                    if (index > 0) {
                        results.append(',');
                    }
                    results.append("{\"index\":").append(index)
                            .append(",\"relevance_score\":0.5}");
                }
                results.append("]}");
                respond(exchange, 200, results.toString());
            });
            List<RerankDocument> documents = new java.util.ArrayList<>();
            for (int index = 0; index < size; index++) {
                documents.add(document("doc-" + index, "title", "text " + index));
            }

            RerankCallResult result = adapter(3000).rerank(provider("NONE", null),
                    settings(3000, "sigmoid"), "q", documents, 8, null);

            assertThat(result.success()).as("size=%s", size).isTrue();
            assertThat(result.scores()).as("size=%s", size).hasSize(size);
            assertThat(requestedTopN.get()).as("size=%s", size).isEqualTo(size);
            assertThat(calls).as("size=%s", size).hasValue(1);
            assertThat(result.scores()).allSatisfy(score ->
                    assertThat(Double.isFinite(score.score())).isTrue());
            server.stop(0);
            server = null;
        }
    }

    private LlamaCppRerankAdapter adapter(long timeoutMs) {
        return new LlamaCppRerankAdapter(objectMapper);
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/rerank", exchange -> handler.handle(exchange));
        server.start();
    }

    private CapabilityProvider provider(String authType, String encryptedApiKey) {
        return new CapabilityProvider(UUID.randomUUID(), "local-rerank", ModelProviderType.LOCAL_CUSTOM,
                "LLAMACPP_RERANK", "http://127.0.0.1:" + server.getAddress().getPort(), "/rerank",
                authType, "local", "/health", encryptedApiKey, true, "PUBLISHED",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static RerankSettings settings(int timeoutMs, String scoreType) {
        return new RerankSettings("LLAMACPP_RERANK", timeoutMs, 50, 8, 2048, scoreType, 8,
                "/health", "java-heuristic");
    }

    private static RerankDocument document(String id, String title, String text) {
        return new RerankDocument(id, title, text);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
