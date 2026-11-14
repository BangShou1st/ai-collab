package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Local SSE framing keeps event names, multi-line data, [DONE], and missing-DONE behavior intact. */
class JsonHttpModelClientSseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dispatchesEventAndMultiLineDataAndStopsOnDone() throws Exception {
        JsonHttpModelClient client = clientFor("""
                event: delta
                data: {"value":
                data: 7}

                data: [DONE]

                """);
        List<String> events = new ArrayList<>();
        List<JsonNode> payloads = new ArrayList<>();
        try {
            client.stream("https://model.example/v1/chat", Map.of(), mapper.createObjectNode(),
                    (event, data) -> {
                        events.add(event);
                        payloads.add(data);
                    });
        } finally {
            client.close();
        }

        assertThat(events).containsExactly("delta", "done");
        assertThat(payloads.get(0).path("value").asInt()).isEqualTo(7);
        assertThat(payloads.get(1)).isNull();
    }

    @Test
    void missingDoneSentinelFailsClosed() throws Exception {
        JsonHttpModelClient client = clientFor("data: {\"value\":1}\n\n");
        try {
            assertThatThrownBy(() -> client.stream("https://model.example/v1/chat", Map.of(),
                    mapper.createObjectNode(), (event, data) -> { }))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
        } finally {
            client.close();
        }
    }

    @SuppressWarnings("unchecked")
    private JsonHttpModelClient clientFor(String wire) throws Exception {
        HttpClient transport = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("text/event-stream")), (a, b) -> true));
        when(response.body()).thenAnswer(invocation ->
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
        when(transport.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(response);
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        return new JsonHttpModelClient(mapper, policy, transport, Duration.ofSeconds(5));
    }
}
