package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.shitulelv.aicollab.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpEndpointPolicyTest {
    private final McpEndpointPolicy policy = new McpEndpointPolicy(
            Set.of("mcp.github.example"),
            host -> new InetAddress[]{InetAddress.getByName(switch (host) {
                case "mcp.github.example" -> "8.8.8.8";
                case "loop.example" -> "127.0.0.1";
                case "metadata.example" -> "169.254.169.254";
                case "private.example" -> "10.10.2.3";
                default -> "1.1.1.1";
            })});

    @Test
    void allowsConfiguredHttpsHostWithPublicResolution() {
        assertThatCode(() -> policy.validate(URI.create("https://mcp.github.example/mcp")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnapprovedHostInsecureSchemeUserInfoAndPrivateDestinations() {
        assertForbidden("https://other.example/mcp");
        assertForbidden("http://mcp.github.example/mcp");
        assertForbidden("https://user:pass@mcp.github.example/mcp");

        McpEndpointPolicy broad = new McpEndpointPolicy(
                Set.of("loop.example", "metadata.example", "private.example"), policy.resolver());
        assertThatThrownBy(() -> broad.validate(URI.create("https://loop.example/mcp")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broad.validate(URI.create("https://metadata.example/mcp")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broad.validate(URI.create("https://private.example/mcp")))
                .isInstanceOf(BusinessException.class);
    }

    private void assertForbidden(String endpoint) {
        assertThatThrownBy(() -> policy.validate(URI.create(endpoint)))
                .isInstanceOf(BusinessException.class);
    }
}
