package com.shitulelv.aicollab.internal.ai.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IndexAdminClient} 单元测试（契约 §4.6、R4-3、R4-4；不依赖 Docker）。
 */
@ExtendWith(OutputCaptureExtension.class)
class IndexAdminClientTest {

    private static final UUID GEN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DOC_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ADMIN_TOKEN = String.join("-", "admin", "secret", "token", "value");

    private HttpClient http;
    private InternalTokenProvider tokens;
    private IndexAdminClient client;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        tokens = mock(InternalTokenProvider.class);
        when(tokens.indexAdminToken()).thenReturn(ADMIN_TOKEN);
        client = new IndexAdminClient(AiIndexProperties.defaults(), tokens, json, http);
    }

    @Test
    void listGenerationsSupportsArrayAndObjectWrapperAndCarriesAdminToken() throws Exception {
        String body = """
                {"generations":[{
                  "id":"%s","embeddingModel":"qwen3-embedding:0.6b","dimension":1024,
                  "status":"ACTIVE","documentCount":23,"rowCount":175,
                  "createdAt":"2026-11-27T08:00:00Z","activatedAt":"2026-11-27T08:05:00Z"
                }]}
                """.formatted(GEN_ID);
        stub(200, body);

        List<IndexAdminClient.GenerationInfo> list = client.listGenerations();

        assertThat(list).hasSize(1);
        IndexAdminClient.GenerationInfo info = list.getFirst();
        assertThat(info.id()).isEqualTo(GEN_ID);
        assertThat(info.embeddingModel()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(info.dimension()).isEqualTo(1024);
        assertThat(info.status()).isEqualTo("ACTIVE");
        assertThat(info.documentCount()).isEqualTo(23);
        assertThat(info.rowCount()).isEqualTo(175);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().method()).isEqualTo("GET");
        assertThat(sent.getValue().uri().toString()).isEqualTo("http://ai-worker:9100/v1/index/generations");
        assertThat(sent.getValue().headers().firstValue("Authorization"))
                .contains(String.join(" ", "Bearer", ADMIN_TOKEN));
    }

    @Test
    void reconcileMissingShortCircuitsEmptyRejectsOver5000AndParsesMissingIds() throws Exception {
        assertThat(client.reconcileMissing(GEN_ID, List.of())).isEmpty();
        verify(http, never()).send(any(), any());

        List<UUID> tooMany = IntStream.range(0, 5001).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> client.reconcileMissing(GEN_ID, tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        verify(http, never()).send(any(), any());

        stub(200, "{\"missingDocumentIds\":[\"" + DOC_2 + "\"]}");
        List<UUID> missing = client.reconcileMissing(GEN_ID, List.of(DOC_1, DOC_2));
        assertThat(missing).containsExactly(DOC_2);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().method()).isEqualTo("POST");
        assertThat(sent.getValue().uri().toString())
                .isEqualTo("http://ai-worker:9100/v1/index/generations/" + GEN_ID + ":reconcile");
        JsonNode payload = json.readTree(readBody(sent.getValue()));
        assertThat(payload.has("mode")).isFalse();
        assertThat(payload.path("documentIds")).hasSize(2);
    }

    @Test
    void reconcilePruneSendsFullSpaceEvenWhenEmptyAndFormatsUtcIsoAsOf() throws Exception {
        OffsetDateTime asOf = OffsetDateTime.of(2026, 11, 27, 16, 0, 0, 123_456_000, ZoneOffset.ofHours(8));
        stub(200, "{\"deletedDocuments\":3,\"deletedRows\":412}");

        IndexAdminClient.PruneResult result = client.reconcilePrune(
                GEN_ID, asOf, new IndexAdminClient.DocumentRange(null, null), List.of());

        assertThat(result.deletedDocuments()).isEqualTo(3L);
        assertThat(result.deletedRows()).isEqualTo(412L);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        JsonNode payload = json.readTree(readBody(sent.getValue()));
        assertThat(payload.path("mode").asText()).isEqualTo("PRUNE");
        assertThat(payload.path("asOf").asText()).isEqualTo("2026-11-27T08:00:00.123456Z");
        assertThat(payload.path("range").has("afterDocumentId")).isTrue();
        assertThat(payload.path("range").get("afterDocumentId").isNull()).isTrue();
        assertThat(payload.path("range").has("throughDocumentId")).isTrue();
        assertThat(payload.path("range").get("throughDocumentId").isNull()).isTrue();
        assertThat(payload.path("documentIds")).isEmpty();
    }

    @Test
    void activateAbandonAndReopenHitExpectedActionPaths() throws Exception {
        stub(200, "{\"status\":\"ACTIVE\"}");
        client.activate(GEN_ID);
        client.abandon(GEN_ID);
        client.reopen(GEN_ID);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(sent.capture(), any());
        assertThat(sent.getAllValues().stream().map(req -> req.uri().getPath()).toList()).containsExactly(
                "/v1/index/generations/" + GEN_ID + ":activate",
                "/v1/index/generations/" + GEN_ID + ":abandon",
                "/v1/index/generations/" + GEN_ID + ":reopen");
    }

    @Test
    void reopenConflictAndNotFoundDoNotRetryAndPreserveSafeCode(CapturedOutput output) throws Exception {
        stub(409, "{\"code\":\"INDEX_GENERATION_CONFLICT\",\"message\":\"another building exists\"}");
        assertThatThrownBy(() -> client.reopen(GEN_ID))
                .isInstanceOfSatisfying(IndexAdminClient.IndexAdminException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(IndexAdminClient.IndexAdminException.Kind.CONFLICT);
                    assertThat(failure.httpStatus()).isEqualTo(409);
                    assertThat(failure.errorCode()).isEqualTo("INDEX_GENERATION_CONFLICT");
                });
        verify(http, times(1)).send(any(), any());

        setUp();
        stub(404, "{\"code\":\"NOT_FOUND\"}");
        assertThatThrownBy(() -> client.activate(GEN_ID))
                .isInstanceOfSatisfying(IndexAdminClient.IndexAdminException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(IndexAdminClient.IndexAdminException.Kind.NOT_FOUND);
                    assertThat(failure.httpStatus()).isEqualTo(404);
                });
        verify(http, times(1)).send(any(), any());
        assertThat(output.getAll()).doesNotContain(ADMIN_TOKEN);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void server5xxAndTransportFailureRetryOnceWithFreshToken() throws Exception {
        HttpResponse failure = response(503, "{\"code\":\"UNAVAILABLE\"}");
        HttpResponse success = response(200, "{\"status\":\"ACTIVE\"}");
        when(http.send(any(), any())).thenReturn(failure).thenReturn(success);

        client.activate(GEN_ID);

        verify(http, times(2)).send(any(), any());
        verify(tokens, times(2)).indexAdminToken();

        setUp();
        when(http.send(any(), any())).thenThrow(new ConnectException("refused"));
        assertThatThrownBy(() -> client.abandon(GEN_ID))
                .isInstanceOfSatisfying(IndexAdminClient.IndexAdminException.class, err ->
                        assertThat(err.kind()).isEqualTo(IndexAdminClient.IndexAdminException.Kind.UNAVAILABLE));
        verify(http, times(2)).send(any(), any());

        setUp();
        when(http.send(any(), any())).thenThrow(new HttpTimeoutException("slow"));
        assertThatThrownBy(() -> client.abandon(GEN_ID))
                .isInstanceOfSatisfying(IndexAdminClient.IndexAdminException.class, err ->
                        assertThat(err.kind()).isEqualTo(IndexAdminClient.IndexAdminException.Kind.UNAVAILABLE));
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void unsupportedOrBlankTokenFailsClosedWithoutCallingHttp(CapturedOutput output) throws Exception {
        InternalTokenProvider legacy = (u, p) -> "plan-only";
        IndexAdminClient noToken = new IndexAdminClient(AiIndexProperties.defaults(), legacy, json, http);
        assertThatThrownBy(noToken::listGenerations)
                .isInstanceOfSatisfying(IndexAdminClient.IndexAdminException.class, err ->
                        assertThat(err.kind()).isEqualTo(IndexAdminClient.IndexAdminException.Kind.UNAUTHORIZED));
        verify(http, never()).send(any(), any());
        assertThat(output.getAll()).doesNotContain(ADMIN_TOKEN);
    }

    @Test
    void propertiesClampOutOfRangeValues() {
        AiIndexProperties clamped = new AiIndexProperties(" http://127.0.0.1:9100/ ", 10, Duration.ZERO,
                Duration.ofSeconds(-1));
        assertThat(clamped.workerBaseUrl()).isEqualTo("http://127.0.0.1:9100");
        assertThat(clamped.requestTimeoutMs()).isEqualTo(1000);
        assertThat(clamped.pruneInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(clamped.pruneInitialDelay()).isEqualTo(Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stub(int status, String body) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = response(status, body);
        when(http.send(any(), any())).thenReturn((HttpResponse) response);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static String readBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        List<ByteBuffer> chunks = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                chunks.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        int total = chunks.stream().mapToInt(ByteBuffer::remaining).sum();
        byte[] bytes = new byte[total];
        int offset = 0;
        for (ByteBuffer chunk : chunks) {
            int len = chunk.remaining();
            chunk.get(bytes, offset, len);
            offset += len;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
