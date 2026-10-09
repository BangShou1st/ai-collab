package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaEmbeddingHttpTest {
    private HttpServer server;
    private ProjectEmbeddingGateway gateway;
    private String origin;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new ProjectEmbeddingGateway(mock(SystemEmbeddingConfigRepository.class), mock(ModelSecretCipher.class));
        gateway.configureEndpoints(new EmbeddingEndpointPolicy(new OutboundEndpointPolicy(), "127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }
    private ProjectEmbeddingConfig config(int dimensions) { return new ProjectEmbeddingConfig(null, "OLLAMA", origin,
            "/v1/embeddings", null, "installed-synthetic-model", dimensions, 2, true, OffsetDateTime.now(), OffsetDateTime.now()); }
    private void reply(int status, String response) {
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet(); request.set(json.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }
    @Test void no_key_and_natural_dimension_probe_use_compatible_protocol() {
        reply(200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        var batch = gateway.embedWithConfig(config(0), List.of("合成文本"), EmbeddingProgressListener.NONE);
        assertThat(batch.dimension()).isEqualTo(3); assertThat(authorization.get()).isNull();
        assertThat(request.get().path("model").asText()).isEqualTo("installed-synthetic-model");
        assertThat(request.get().has("dimensions")).isFalse();
        assertThat(request.get().path("input").get(0).asText()).isEqualTo("合成文本");
    }
    @Test void batch_order_is_verified_and_sorted() {
        reply(200, "{\"data\":[{\"index\":1,\"embedding\":[1,2,3]},{\"index\":0,\"embedding\":[4,5,6]}]}");
        var batch = gateway.embedWithConfig(config(3), List.of("a","b"), EmbeddingProgressListener.NONE);
        assertThat(batch.vectors()).containsExactly(List.of(4d,5d,6d), List.of(1d,2d,3d));
    }
    @Test void changed_natural_dimension_is_rejected() {
        reply(200, "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}");
        assertThatThrownBy(() -> gateway.embedWithConfig(config(3), List.of("a"), EmbeddingProgressListener.NONE)).isInstanceOf(BusinessException.class);
    }
    @Test void duplicate_index_is_not_silently_accepted() {
        reply(200, "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]},{\"index\":0,\"embedding\":[1,2,3]}]}");
        assertThatThrownBy(() -> gateway.embedWithConfig(config(3), List.of("a","b"), EmbeddingProgressListener.NONE)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(ints={401,403,429}) void auth_and_rate_limit_stop_immediately(int status) {
        reply(status, "{\"error\":\"synthetic failure\"}");
        assertThatThrownBy(() -> gateway.embedWithConfig(config(3), List.of("a"), EmbeddingProgressListener.NONE))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(status == 429 ? ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED : ErrorCode.AI_MODEL_CREDENTIAL_INVALID));
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void local_exception_does_not_allow_other_targets_or_protocols() {
        var policy = new EmbeddingEndpointPolicy(new OutboundEndpointPolicy(), "127.0.0.1:11434");
        assertThat(policy.require("OLLAMA", "http://127.0.0.1:11434", "/v1/embeddings")).isNotNull();
        for (String base : List.of("http://127.0.0.1:11435","http://192.168.0.1:11434","http://u:p@127.0.0.1:11434"))
            assertThatThrownBy(() -> policy.require("OLLAMA", base, "/v1/embeddings")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.require("OLLAMA", "http://127.0.0.1:11434", "/api/embed")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.require("OPENAI_COMPATIBLE", "http://127.0.0.1:11434", "/v1/embeddings")).isInstanceOf(RuntimeException.class);
    }
}
