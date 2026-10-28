package com.shitulelv.aicollab.infrastructure.ai.embedding;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gemini OpenAI 兼容 embeddings 响应不含 data[].index，网关必须按位置对齐。 */
class ProjectEmbeddingGatewayResponseCompatTest {

    @Test
    void response_without_index_aligns_input_by_position() {
        EmbeddingBatch batch = embed("""
                {"object":"list","data":[
                  {"object":"embedding","embedding":[9.0,9.0,9.0]},
                  {"object":"embedding","embedding":[1.0,1.0,1.0]}],
                 "model":"gemini-embedding-001"}
                """);
        assertThat(batch.dimension()).isEqualTo(3);
        assertThat(batch.vectors()).hasSize(2);
        assertThat(batch.vectors().get(0)).containsExactly(9.0, 9.0, 9.0);
        assertThat(batch.vectors().get(1)).containsExactly(1.0, 1.0, 1.0);
    }

    @Test
    void response_with_index_keeps_index_alignment() {
        EmbeddingBatch batch = embed("""
                {"object":"list","data":[
                  {"index":0,"embedding":[1.0]},
                  {"index":1,"embedding":[2.0]}],
                 "model":"test-model"}
                """);
        assertThat(batch.vectors().get(0)).containsExactly(1.0);
        assertThat(batch.vectors().get(1)).containsExactly(2.0);
    }

    @Test
    void partially_missing_index_is_rejected() {
        assertThatThrownBy(() -> embed("""
                {"object":"list","data":[
                  {"index":0,"embedding":[1.0]},
                  {"object":"embedding","embedding":[2.0]}],
                 "model":"test-model"}
                """))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.DOCUMENT_EMBEDDING_FAILED));
    }

    private static EmbeddingBatch embed(String responseBody) {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(host -> publicAddress());
        ClientHttpRequestFactory factory = (uri, method) -> new StubHttpResponse(responseBody);
        RestClient transport = RestClient.builder().requestFactory(factory).build();
        SystemEmbeddingConfigRepository repository = mock(SystemEmbeddingConfigRepository.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        ProjectEmbeddingGateway gateway = new ProjectEmbeddingGateway(repository, secrets, policy, transport);
        ProjectEmbeddingConfig config = new ProjectEmbeddingConfig(UUID.randomUUID(), "OPENAI_COMPATIBLE",
                "https://embedding.example", "/v1/embeddings", "encrypted", "model", 0, 2,
                true, OffsetDateTime.now(), OffsetDateTime.now());
        when(secrets.decrypt("encrypted")).thenReturn("secret");
        return gateway.embedWithConfig(config, List.of("first", "second"), EmbeddingProgressListener.NONE);
    }

    private static InetAddress[] publicAddress() {
        try {
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record StubHttpResponse(String body) implements ClientHttpRequest {
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public URI getURI() { return URI.create("https://embedding.example/v1/embeddings"); }
        @Override public java.util.Map<String, Object> getAttributes() { return new java.util.HashMap<>(); }
        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders(new org.springframework.util.LinkedMultiValueMap<>());
            headers.set("Content-Type", "application/json");
            return headers;
        }
        @Override public OutputStream getBody() { return OutputStream.nullOutputStream(); }
        @Override public ClientHttpResponse execute() {
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(200); }
                @Override public String getStatusText() { return "OK"; }
                @Override public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders(new org.springframework.util.LinkedMultiValueMap<>());
                    headers.set("Content-Type", "application/json");
                    return headers;
                }
                @Override public InputStream getBody() {
                    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                }
                @Override public void close() { }
            };
        }
    }
}
