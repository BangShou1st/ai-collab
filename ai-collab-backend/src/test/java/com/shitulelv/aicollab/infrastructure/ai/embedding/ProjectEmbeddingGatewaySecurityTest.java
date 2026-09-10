package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectEmbeddingGatewaySecurityTest {
    @Test
    void embedding_runtime_revalidates_endpoint() {
        AtomicReference<InetAddress[]> addresses = new AtomicReference<>(publicAddress());
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(host -> addresses.get());
        RestClient transport = mock(RestClient.class);
        SystemEmbeddingConfigRepository repository = mock(SystemEmbeddingConfigRepository.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        ProjectEmbeddingGateway gateway = new ProjectEmbeddingGateway(repository, secrets, policy, transport);
        ProjectEmbeddingConfig config = new ProjectEmbeddingConfig(UUID.randomUUID(), "OPENAI_COMPATIBLE",
                "https://embedding.example", "/v1/embeddings", "encrypted", "model", 3, 1,
                true, OffsetDateTime.now(), OffsetDateTime.now());
        when(secrets.decrypt("encrypted")).thenReturn("secret");

        URI endpoint = URI.create("https://embedding.example/v1/embeddings");
        assertThatCode(() -> policy.requirePublicHttps(endpoint)).doesNotThrowAnyException();
        addresses.set(new InetAddress[]{InetAddress.getLoopbackAddress()});

        assertThatThrownBy(() -> gateway.embedWithConfig(config, List.of("hello"), EmbeddingProgressListener.NONE))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(transport);
    }

    private static InetAddress[] publicAddress() {
        try {
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
