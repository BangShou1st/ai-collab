package com.shitulelv.aicollab.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalOutboundTargetPolicyTest {

    private static final Map<String, String[]> DNS = Map.of(
            "localhost", new String[]{"127.0.0.1", "::1"},
            "loop.example", new String[]{"127.0.0.1"},
            "mixed.example", new String[]{"127.0.0.1", "10.0.0.5"},
            "private.example", new String[]{"10.0.0.5"});

    @Test
    void allowsExactLoopbackTargetInLocalOrTestProfile() throws Exception {
        LocalOutboundTargetPolicy policy = policy("127.0.0.1:3425,localhost:3425", "test");

        assertThat(policy.allows(URI.create("http://127.0.0.1:3425/v1/chat/completions")))
                .isTrue();
        assertThat(policy.allows(URI.create("http://localhost:3425/v1/chat/completions")))
                .isTrue();
        assertThat(policy.allows(URI.create("https://127.0.0.1:3425/v1/chat/completions")))
                .isTrue();
    }

    @Test
    void rejectsNonExactPortHostAndScheme() {
        LocalOutboundTargetPolicy policy = policy("127.0.0.1:3425", "local");

        assertThat(policy.allows(URI.create("http://127.0.0.1:3426/v1/chat/completions"))).isFalse();
        assertThat(policy.allows(URI.create("http://127.0.0.2:3425/v1/chat/completions"))).isFalse();
        assertThat(policy.allows(URI.create("ftp://127.0.0.1:3425/file"))).isFalse();
        assertThat(policy.allows(URI.create("http://user@127.0.0.1:3425/v1"))).isFalse();
        assertThat(policy.allows(URI.create("http://127.0.0.1:3425/v1#fragment"))).isFalse();
    }

    @Test
    void rejectsTargetsThatAreNotAllLoopbackAfterDnsResolution() {
        LocalOutboundTargetPolicy policy = policy(
                "mixed.example:3425,private.example:3425,loop.example:3425", "local");

        assertThat(policy.allows(URI.create("http://mixed.example:3425/v1"))).isFalse();
        assertThat(policy.allows(URI.create("http://private.example:3425/v1"))).isFalse();
        assertThat(policy.allows(URI.create("http://loop.example:3425/v1"))).isTrue();
    }

    @Test
    void rejectsUnsafeOrMalformedConfiguration() {
        for (String target : new String[]{
                "localhost", "127.0.0.1:0", "127.0.0.1:65536", "*:3425",
                "http://127.0.0.1:3425", "127.0.0.1:3425/path"}) {
            assertThatThrownBy(() -> policy(target, "test"))
                    .as(target)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host:port");
        }
    }

    @Test
    void nonLocalProfileFailsFastWhenWhitelistIsConfigured() {
        assertThatThrownBy(() -> policy("127.0.0.1:3425", "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local/test");

        assertThatCodeDoesNotThrowForEmptyWhitelistInProduction();
    }

    private static void assertThatCodeDoesNotThrowForEmptyWhitelistInProduction() {
        LocalOutboundTargetPolicy policy = policy("", "production");
        assertThat(policy).isNotNull();
    }

    private static LocalOutboundTargetPolicy policy(String targets, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        LocalOutboundTargetPolicy policy = new LocalOutboundTargetPolicy(
                targets, environment, host -> addresses(host));
        policy.afterPropertiesSet();
        return policy;
    }

    private static InetAddress[] addresses(String host) throws Exception {
        String[] resolved = DNS.getOrDefault(host, new String[]{"127.0.0.1"});
        InetAddress[] addresses = new InetAddress[resolved.length];
        for (int index = 0; index < resolved.length; index++) {
            addresses[index] = InetAddress.getByName(resolved[index]);
        }
        return addresses;
    }
}
