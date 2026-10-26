package com.shitulelv.aicollab.common.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ModelHttpProxyTest {
    private static ProxySelector routes(Proxy... routes) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(routes); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        };
    }
    private static Proxy proxy(int port) { return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)); }
    private static EnvironmentProxySelector selector(ProxySelector nativeSelector) {
        return new EnvironmentProxySelector(nativeSelector, name -> null, Map.of());
    }

    @Test void nativeProxyIsUsedBySyncStreamAndDiscoveryAndRedirectsStayDisabled() throws Exception {
        AtomicInteger proxyRequests = new AtomicInteger();
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            proxyRequests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "http://model.example.invalid/redirected");
                exchange.sendResponseHeaders(302, -1);
                exchange.close(); return;
            }
            if (path.equals("/redirected")) redirectedRequests.incrementAndGet();
            String body = path.equals("/stream") ? "data: {\"ok\":true}\n\ndata: [DONE]\n\n" : "{\"ok\":true}";
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", path.equals("/stream") ? "text/event-stream" : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        // Only this isolated fake endpoint accepts HTTP. Production OutboundEndpointPolicy is unchanged.
        OutboundEndpointPolicy fakeEndpoint = new OutboundEndpointPolicy() {
            @Override public URI requirePublicHttps(URI endpoint) { return endpoint; }
        };
        try (HttpClient transport = OutboundHttpClients.builder().proxy(selector(routes(proxy(server.getAddress().getPort()))))
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            JsonHttpModelClient client = new JsonHttpModelClient(new ObjectMapper(), fakeEndpoint, transport, Duration.ofSeconds(3));
            try {
                var body = new ObjectMapper().createObjectNode().put("probe", true);
                assertThat(client.post("http://model.example.invalid/json", Map.of(), body).path("ok").asBoolean()).isTrue();
                assertThat(client.get("http://model.example.invalid/models", Map.of()).path("ok").asBoolean()).isTrue();
                AtomicInteger chunks = new AtomicInteger();
                AtomicInteger done = new AtomicInteger();
                client.stream("http://model.example.invalid/stream", Map.of(), body, (event, chunk) -> {
                    if (chunk != null) chunks.incrementAndGet();
                    if ("done".equals(event)) done.incrementAndGet();
                });
                assertThat(chunks).hasValue(1);
                assertThat(done).hasValue(1);
                assertThatThrownBy(() -> client.get("http://model.example.invalid/redirect", Map.of())).isInstanceOf(RuntimeException.class);
                assertThat(proxyRequests).hasValue(4);
                assertThat(redirectedRequests).hasValue(0);
                assertThat(transport.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                // Even when a proxy is configured, private/HTTP/userinfo endpoints are rejected before transport.
                JsonHttpModelClient guarded = new JsonHttpModelClient(new ObjectMapper(), new OutboundEndpointPolicy(), transport);
                try {
                    for (String forbidden : List.of("http://model.example.invalid/", "https://127.0.0.1/", "https://user:secret@example.test/"))
                        assertThatThrownBy(() -> guarded.get(forbidden, Map.of())).isInstanceOf(RuntimeException.class);
                    assertThat(proxyRequests).hasValue(4);
                } finally { guarded.close(); }
            } finally { client.close(); }
        } finally { server.stop(0); }
    }

    @Test void unreachableConfiguredProxyFailsInsteadOfContactingReachableOrigin() throws Exception {
        InetAddress localAddress = java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(n -> { try { return n.isUp(); } catch (Exception failure) { return false; } })
                .flatMap(n -> java.util.Collections.list(n.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()).findFirst().orElseThrow();
        AtomicInteger originRequests = new AtomicInteger();
        HttpServer origin = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        origin.createContext("/", exchange -> { originRequests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        origin.start();
        URI target = URI.create("http://" + localAddress.getHostAddress() + ":" + origin.getAddress().getPort() + "/");
        int unusedPort;
        try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { unusedPort = reserve.getLocalPort(); }
        try {
            try (HttpClient direct = OutboundHttpClients.builder().proxy(selector(routes(Proxy.NO_PROXY))).build()) {
                assertThat(direct.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
            }
            originRequests.set(0);
            var pinned = selector(routes(proxy(unusedPort), Proxy.NO_PROXY));
            try (HttpClient client = OutboundHttpClients.builder().proxy(pinned).connectTimeout(Duration.ofSeconds(1)).build()) {
                assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()))
                        .isInstanceOf(IOException.class);
                assertThat(originRequests).hasValue(0);
                assertThat(pinned.select(target)).hasSize(1).doesNotContain(Proxy.NO_PROXY);
            }
        } finally { origin.stop(0); }
    }

    @Test void actualChildJvmHonorsExplicitJvmAndEnvironmentProxySettings() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        proxy.start();
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            for (boolean explicitJvm : new boolean[]{true, false}) {
                var command = new java.util.ArrayList<>(List.of(java, "-Djava.net.useSystemProxies=false"));
                if (explicitJvm) command.addAll(List.of("-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=" + proxy.getAddress().getPort()));
                command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PropertyProbe.class.getName()));
                ProcessBuilder builder = new ProcessBuilder(command);
                for (String variable : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy"))
                    builder.environment().remove(variable);
                builder.environment().put("HTTP_PROXY", "http://127.0.0.1:" + (explicitJvm ? 1 : proxy.getAddress().getPort()));
                Process child = builder.start();
                try {
                    assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
                    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(child.exitValue()).isZero();
                    assertThat(output).contains(explicitJvm ? "JVM_PROPERTIES" : "ENVIRONMENT_FALLBACK", "204");
                } finally { if (child.isAlive()) child.destroyForcibly(); }
            }
            assertThat(requests).hasValue(2);
        } finally { proxy.stop(0); }
    }

    public static class PropertyProbe {
        public static void main(String[] args) throws Exception {
            URI target = URI.create("http://child.example.invalid/probe");
            try (HttpClient client = OutboundHttpClients.builder().connectTimeout(Duration.ofSeconds(2)).build()) {
                System.out.println(((EnvironmentProxySelector) client.proxy().orElseThrow()).route(target).source());
                System.out.println(client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }
}
