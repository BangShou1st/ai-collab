package com.shitulelv.aicollab.common.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class EnvironmentProxySelectorTest {
    private static final URI TARGET = URI.create("https://model.example.test/");
    private static Proxy proxy(int port) {
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.test", port));
    }
    private static ProxySelector nativeRoutes(Proxy... proxies) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(proxies); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                throw new AssertionError("Must not rotate proxy after failure");
            }
        };
    }
    private static EnvironmentProxySelector selector(ProxySelector nativeSelector, Map<String, String> properties,
                                                    Map<String, String> environment) {
        return new EnvironmentProxySelector(nativeSelector, properties::get, environment);
    }

    @Test void usesNativeProxyBeforeEnvironmentAndNeverOffersDirectFallback() {
        var selector = selector(nativeRoutes(proxy(3128), Proxy.NO_PROXY), Map.of(),
                Map.of("HTTPS_PROXY", "http://user:secret@wrong.example:9999"));
        assertThat(selector.select(TARGET)).containsExactly(proxy(3128));
        selector.connectFailed(TARGET, proxy(3128).address(), new IOException("sensitive failure text"));
        assertThat(selector.select(TARGET)).containsExactly(proxy(3128));
        assertThat(selector.route(TARGET).source()).isEqualTo("JDK_SYSTEM_SELECTOR");
    }

    @Test void explicitJvmPropertiesAndItsBypassRemainAuthoritative() {
        var selector = selector(nativeRoutes(Proxy.NO_PROXY), Map.of("https.proxyHost", "proxy.example.test", "https.proxyPort", "3128"),
                Map.of("HTTPS_PROXY", "http://another.example:8080"));
        assertThat(selector.select(TARGET)).containsExactly(Proxy.NO_PROXY);
        assertThat(selector.route(TARGET).source()).isEqualTo("JVM_PROPERTIES");
    }

    @Test void supportsExplicitEnvironmentFallbackAndProtocolSpecificVariables() {
        var selector = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of(
                "HTTPS_PROXY", "http://secure.example:3128", "HTTP_PROXY", "http://plain.example:8080"));
        assertThat(((InetSocketAddress) selector.select(TARGET).getFirst().address()).getHostString()).isEqualTo("secure.example");
        assertThat(((InetSocketAddress) selector.select(URI.create("http://model.example.test/")).getFirst().address()).getPort()).isEqualTo(8080);
        assertThat(selector.route(TARGET).source()).isEqualTo("ENVIRONMENT_FALLBACK");
        var lower = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("https_proxy", "http://lower.example"));
        assertThat(((InetSocketAddress) lower.select(TARGET).getFirst().address()).getPort()).isEqualTo(80);
        var all = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("ALL_PROXY", "http://all.example:8080"));
        assertThat(all.select(TARGET).getFirst().type()).isEqualTo(Proxy.Type.HTTP);
    }

    @Test void noConfigurationAllowsDirectWithoutModifyingGlobalSelector() {
        ProxySelector global = ProxySelector.getDefault();
        assertThat(selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of()).select(TARGET)).containsExactly(Proxy.NO_PROXY);
        assertThat(selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("HTTPS_PROXY", "", "NO_PROXY", "")).select(TARGET))
                .containsExactly(Proxy.NO_PROXY);
        EnvironmentProxySelector.system();
        assertThat(ProxySelector.getDefault()).isSameAs(global);
    }

    @Test void localServicesBypassEvenNativeOsProxyAndInvalidRemoteEnvironment() {
        AtomicInteger calls = new AtomicInteger();
        ProxySelector os = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { calls.incrementAndGet(); return List.of(proxy(3128)); }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };
        var selector = selector(os, Map.of(), Map.of("HTTPS_PROXY", "http://user:password@bad.example"));
        for (String host : List.of("localhost", "service.localhost", "127.0.0.1", "127.1.2.3", "[::1]"))
            assertThat(selector.select(URI.create("https://" + host + ":8443/"))).containsExactly(Proxy.NO_PROXY);
        assertThat(calls).hasValue(0);
    }

    @Test void noProxyMatchesDomainsPortsAndWildcardWithoutSubstringConfusion() {
        var selector = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of(
                "HTTPS_PROXY", "http://proxy.example:8080", "NO_PROXY", ".example.test:443,exact.test"));
        assertThat(selector.select(TARGET)).containsExactly(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://example.test/"))).containsExactly(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://notexample.test/"))).doesNotContain(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://model.example.test:8443/"))).doesNotContain(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://exact.test/"))).containsExactly(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://sub.exact.test/"))).doesNotContain(Proxy.NO_PROXY);
        var all = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("HTTPS_PROXY", "http://proxy.example", "NO_PROXY", "*"));
        assertThat(all.select(TARGET)).containsExactly(Proxy.NO_PROXY);
    }

    @Test void badProxyAndUnsupportedExclusionsAreRejectedWithoutCredentialEcho() {
        for (String bad : List.of("http://user:super-secret@proxy.example:3128", "https://proxy.example:3128",
                "socks5://proxy.example:1080", "http://proxy.example:0", "http://proxy.example:65536",
                "http://proxy.example/path-with-super-secret", "http://proxy.example/?key=super-secret", "bad-super-secret")) {
            var selector = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("HTTPS_PROXY", bad));
            assertThatThrownBy(() -> selector.select(TARGET)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid or unsupported HTTP proxy configuration (details redacted)").hasNoCause();
        }
        var cidr = selector(nativeRoutes(Proxy.NO_PROXY), Map.of(), Map.of("HTTPS_PROXY", "http://proxy.example", "NO_PROXY", "10.0.0.0/8"));
        assertThatThrownBy(() -> cidr.select(TARGET)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        var invalidJvm = selector(nativeRoutes(proxy(8080)), Map.of("https.proxyHost", "user:secret@proxy.example"), Map.of());
        assertThatThrownBy(() -> invalidJvm.select(TARGET)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void diagnosticsNeverContainUriSecretsOrConflateSelectionWithTransport() {
        var selector = selector(nativeRoutes(proxy(3128)), Map.of("java.net.useSystemProxies", "true"), Map.of());
        var diagnostic = selector.diagnostic(URI.create("https://user:secret@model.example.test/path?api_key=secret"));
        assertThat(diagnostic.toString()).doesNotContain("secret", "path", "api_key", "user:");
        assertThat(diagnostic).containsEntry("selection", "PROXY").containsEntry("connectTls", "NOT_TESTED_BY_SELECTION")
                .containsEntry("providerReceipt", "NOT_TESTED");
    }
}
