package com.sharedlibs.opencodezen;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenProtocolPolicyTest {

    @Test
    void appliesVerifiedClientIdentityAndBearerFallback() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.test/models")).GET();
        OpenCodeZenProtocolPolicy.applyIdentityHeaders(builder, "ses_0123456789ab0123456789abcd", "msg_0123456789ab0123456789abcd");
        HttpRequest request = builder.header("Authorization",
                OpenCodeZenProtocolPolicy.authorizationHeader(" ")).build();

        assertThat(request.headers().firstValue("User-Agent")).contains(OpenCodeZenProtocolPolicy.USER_AGENT);
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.CLIENT_HEADER)).contains("cli");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.PROJECT_HEADER)).contains("global");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_HEADER))
                .contains("ses_0123456789ab0123456789abcd");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.SESSION_ID_HEADER))
                .contains("ses_0123456789ab0123456789abcd");
        assertThat(request.headers().firstValue(OpenCodeZenProtocolPolicy.REQUEST_HEADER))
                .contains("msg_0123456789ab0123456789abcd");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer public");
        assertThat(OpenCodeZenProtocolPolicy.authorizationHeader("configured-key"))
                .isEqualTo("Bearer configured-key");
    }

    @Test
    void appliesRequiredChatEnvelopeAndReservesToolNames() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "model-id");
        OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(payload, true);

        assertThat(payload).containsEntry("stream", true)
                .containsEntry("stream_options", Map.of("include_usage", true));
        assertThat(toolNames(payload.get("tools"))).containsExactly("bash", "read");
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search", "read"))).isTrue();
        assertThat(OpenCodeZenProtocolPolicy.hasReservedToolName(List.of("search"))).isFalse();
        assertThat(OpenCodeZenProtocolPolicy.appendReservedTools(List.of(Map.of("name", "search"))))
                .hasSize(3);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolNames(Object value) {
        return ((List<Map<String, Object>>) value).stream()
                .map(tool -> (String) ((Map<String, Object>) tool.get("function")).get("name"))
                .toList();
    }
}
