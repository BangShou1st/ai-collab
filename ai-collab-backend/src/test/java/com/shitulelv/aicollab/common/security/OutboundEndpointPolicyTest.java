package com.shitulelv.aicollab.common.security;

import com.shitulelv.aicollab.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundEndpointPolicyTest {

    private static final Map<String, String> DNS = Map.of(
            "public.example", "93.184.216.34",
            "loop.example", "127.0.0.1",
            "private.example", "10.10.2.3",
            "v6loop.example", "::1",
            "evil.example", "10.0.0.1");

    private final OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
            host -> new InetAddress[]{InetAddress.getByName(DNS.getOrDefault(host, "1.1.1.1"))});

    @Test
    void rejects_loopback_endpoint() {
        assertThatThrownBy(() -> policy.requirePublicHttps(URI.create("https://loop.example/api")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_private_ipv4_endpoint() {
        assertThatThrownBy(() -> policy.requirePublicHttps(URI.create("https://private.example/api")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_ipv6_loopback() {
        assertThatThrownBy(() -> policy.requirePublicHttps(URI.create("https://v6loop.example/api")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_public_host_resolving_private_address() {
        assertThatThrownBy(() -> policy.requirePublicHttps(URI.create("https://evil.example/api")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_redirect_to_private_address() {
        URI start = URI.create("https://public.example/start");
        assertThatThrownBy(() -> policy.followRedirects(start,
                current -> Optional.of(URI.create("https://loop.example/next")), 5))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void allows_public_https_custom_endpoint() {
        URI endpoint = URI.create("https://public.example/v1/chat/completions");
        assertThat(policy.requirePublicHttps(endpoint)).isEqualTo(endpoint);
    }
}
