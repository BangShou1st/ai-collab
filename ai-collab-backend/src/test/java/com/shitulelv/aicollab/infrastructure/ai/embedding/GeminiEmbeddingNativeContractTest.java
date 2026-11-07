package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gemini 原生 batchEmbedContents 协议契约（阶段 2 最小适配）：
 * <ul>
 *   <li>文档/查询任务类型分别发送 RETRIEVAL_DOCUMENT / RETRIEVAL_QUERY（禁止静默忽略任务类型）；</li>
 *   <li>请求模型带 models/ 前缀、outputDimensionality 降维、x-goog-api-key 认证；</li>
 *   <li>响应按序对齐，数量/维度/有限性严格校验，部分成功不得当作全部成功；</li>
 *   <li>429 → AI_PROVIDER_QUOTA_EXCEEDED；400 API key not valid → AI_MODEL_CREDENTIAL_INVALID。</li>
 * </ul>
 */
class GeminiEmbeddingNativeContractTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> SENT_URIS = new CopyOnWriteArrayList<>();
    private static final List<String> SENT_BODIES = new CopyOnWriteArrayList<>();

    @Test
    void documentTask_sendsRetrievalDocumentWithModelsPrefixAndDimension() {
        EmbeddingBatch batch = embed(EmbeddingTaskType.DOCUMENT,
                1536,
                "{\"embeddings\":[{\"values\":%s},{\"values\":%s}]}"
                        .formatted(unitVector(1536, 1.0), unitVector(1536, 2.0)));
        assertThat(batch.dimension()).isEqualTo(1536);
        assertThat(batch.vectors()).hasSize(2);
        JsonNode body = parse(SENT_BODIES.getLast());
        assertThat(body.path("requests").size()).isEqualTo(2);
        assertThat(body.path("requests").get(0).path("model").asText()).isEqualTo("models/gemini-embedding-001");
        assertThat(body.path("requests").get(0).path("taskType").asText()).isEqualTo("RETRIEVAL_DOCUMENT");
        assertThat(body.path("requests").get(0).path("outputDimensionality").asInt()).isEqualTo(1536);
        assertThat(SENT_URIS.getLast()).startsWith("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents");
    }

    @Test
    void queryTask_sendsRetrievalQuery() {
        embed(EmbeddingTaskType.QUERY, 768,
                "{\"embeddings\":[{\"values\":%s},{\"values\":%s}]}"
                        .formatted(unitVector(768, 1.0), unitVector(768, 2.0)));
        JsonNode body = parse(SENT_BODIES.getLast());
        assertThat(body.path("requests").get(0).path("taskType").asText()).isEqualTo("RETRIEVAL_QUERY");
        assertThat(body.path("requests").get(0).path("outputDimensionality").asInt()).isEqualTo(768);
    }

    @Test
    void responseCountMismatch_rejected() {
        assertThatThrownBy(() -> embed(EmbeddingTaskType.DOCUMENT, 1536,
                "{\"embeddings\":[{\"values\":%s}]}".formatted(unitVector(1536, 1.0))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_EMBEDDING_FAILED));
    }

    @Test
    void dimensionMismatch_rejected() {
        assertThatThrownBy(() -> embed(EmbeddingTaskType.DOCUMENT, 1536,
                "{\"embeddings\":[{\"values\":[1.0,2.0]},{\"values\":[3.0,4.0]}]}"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void quota429_mapsToQuotaExceeded() {
        assertThatThrownBy(() -> embed(EmbeddingTaskType.DOCUMENT, 1536, null, 429))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED));
    }

    @Test
    void invalidApiKey400_mapsToCredentialInvalid() {
        assertThatThrownBy(() -> embed(EmbeddingTaskType.DOCUMENT, 1536,
                "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}", 400))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_CREDENTIAL_INVALID));
    }

    @Test
    void geminiFingerprint_bindsTaskTypePairAndProtocol() {
        String plain = EmbeddingFingerprints.fingerprint("GEMINI", "gemini-embedding-001", 1536);
        String nativeFp = EmbeddingFingerprints.geminiFingerprint("gemini-embedding-001",
                "https://generativelanguage.googleapis.com", "/v1beta", 1536, "RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY");
        assertThat(nativeFp).isNotEqualTo(plain);
        String samePair = EmbeddingFingerprints.geminiFingerprint("gemini-embedding-001",
                "https://generativelanguage.googleapis.com", "/v1beta", 1536, "RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY");
        String otherPair = EmbeddingFingerprints.geminiFingerprint("gemini-embedding-001",
                "https://generativelanguage.googleapis.com", "/v1beta", 1536, "SEMANTIC_SIMILARITY/SEMANTIC_SIMILARITY");
        assertThat(samePair).isEqualTo(nativeFp);
        assertThat(otherPair).isNotEqualTo(nativeFp);
    }

    // ========== 夹具 ==========

    private static JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String unitVector(int dim, double scale) {
        double v = scale / Math.sqrt(dim);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) sb.append(',');
            sb.append(v);
        }
        return sb.append(']').toString();
    }

    private static EmbeddingBatch embed(EmbeddingTaskType taskType, int dimensions, String responseBody) {
        return embed(taskType, dimensions, responseBody, 200);
    }

    private static EmbeddingBatch embed(EmbeddingTaskType taskType, int dimensions,
            String responseBody, int status) {
        SENT_URIS.clear();
        SENT_BODIES.clear();
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(host -> publicAddress());
        ClientHttpRequestFactory factory = (uri, method) ->
                new RecordingStubRequest(uri, responseBody, status);
        RestClient transport = RestClient.builder().requestFactory(factory).build();
        SystemEmbeddingConfigRepository repository = mock(SystemEmbeddingConfigRepository.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt("encrypted")).thenReturn("gemini-secret");
        ProjectEmbeddingGateway gateway = new ProjectEmbeddingGateway(repository, secrets, policy, transport);
        ProjectEmbeddingConfig config = new ProjectEmbeddingConfig(UUID.randomUUID(), "GEMINI",
                "https://generativelanguage.googleapis.com", "/v1beta", "encrypted",
                "gemini-embedding-001", dimensions, 64, true, OffsetDateTime.now(), OffsetDateTime.now());
        List<String> inputs = status == 200
                ? List.of("第一段文本", "第二段文本")
                : List.of("第一段文本");
        return gateway.embedWithConfig(config, inputs, EmbeddingProgressListener.NONE, taskType);
    }

    private static InetAddress[] publicAddress() {
        try {
            return new InetAddress[]{InetAddress.getByName("142.250.196.174")};
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingStubRequest implements ClientHttpRequest {
        private final URI uri;
        private final String body;
        private final int status;
        private final StringBuilder captured = new StringBuilder();
        RecordingStubRequest(URI uri, String body, int status) { this.uri = uri; this.body = body; this.status = status; }
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public URI getURI() { return uri; }
        @Override public java.util.Map<String, Object> getAttributes() { return new java.util.HashMap<>(); }
        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders(new org.springframework.util.LinkedMultiValueMap<>());
            headers.set("Content-Type", "application/json");
            return headers;
        }
        @Override public OutputStream getBody() {
            return new OutputStream() {
                @Override public void write(int b) { captured.append((char) b); }
                @Override public void write(byte[] buffer, int offset, int length) {
                    captured.append(new String(buffer, offset, length, StandardCharsets.UTF_8));
                }
            };
        }
        @Override public ClientHttpResponse execute() {
            SENT_URIS.add(uri.toString());
            SENT_BODIES.add(captured.toString());
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(status); }
                @Override public String getStatusText() { return String.valueOf(status); }
                @Override public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders(new org.springframework.util.LinkedMultiValueMap<>());
                    headers.set("Content-Type", "application/json");
                    return headers;
                }
                @Override public InputStream getBody() {
                    return new ByteArrayInputStream((body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8));
                }
                @Override public void close() { }
            };
        }
    }
}
