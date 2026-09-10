package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JsonHttpModelClientSecurityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void model_runtime_revalidates_endpoint() {
        AtomicReference<InetAddress[]> addresses = new AtomicReference<>(publicAddress());
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(host -> addresses.get());
        HttpClient transport = mock(HttpClient.class);
        JsonHttpModelClient client = new JsonHttpModelClient(mapper, policy, transport);

        URI endpoint = URI.create("https://model.example/v1/chat");
        assertThatCode(() -> policy.requirePublicHttps(endpoint)).doesNotThrowAnyException();

        addresses.set(new InetAddress[]{InetAddress.getLoopbackAddress()});
        assertThatThrownBy(() -> client.post(endpoint.toString(), Map.of(), mapper.createObjectNode(), 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(transport);
    }

    @Test
    void model_stream_revalidates_endpoint() throws Exception {
        AtomicReference<InetAddress[]> addresses = new AtomicReference<>(publicAddress());
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(host -> addresses.get());
        HttpClient transport = mock(HttpClient.class);
        JsonHttpModelClient client = new JsonHttpModelClient(mapper, policy, transport);

        URI endpoint = URI.create("https://model.example/v1/chat");
        assertThatCode(() -> policy.requirePublicHttps(endpoint)).doesNotThrowAnyException();

        addresses.set(new InetAddress[]{InetAddress.getByName("fd00::1234")});
        assertThatThrownBy(() -> client.stream(endpoint.toString(), Map.of(), mapper.createObjectNode(), (event, data) -> { }))
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
