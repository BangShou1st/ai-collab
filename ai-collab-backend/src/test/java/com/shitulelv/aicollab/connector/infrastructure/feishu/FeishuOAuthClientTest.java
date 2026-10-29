package com.shitulelv.aicollab.connector.infrastructure.feishu;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuOAuthClientTest {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void refreshUsesV3FormAndReturnsBothRotatedTokensWithoutLoggingThem() throws Exception {
        FeishuOAuthClient client = server(200, """
                {"code":0,"access_token":"synthetic-access","refresh_token":"synthetic-refresh-new",
                 "expires_in":1800,"refresh_token_expires_in":3600,"scope":"offline_access docx:document:readonly"}
                """);
        FeishuOAuthClient.Tokens result = client.refresh("synthetic-client", "synthetic-client-secret", "synthetic-old");
        assertEquals("synthetic-refresh-new", result.refreshToken());
        assertEquals(NOW.plusSeconds(1800), result.accessExpiresAt());
        assertEquals(NOW.plusSeconds(3600), result.refreshExpiresAt());
        assertTrue(requestBody.get().contains("grant_type=refresh_token"));
        assertEquals(1, requests.get());
        assertFalse(result.toString().contains("synthetic"));
    }

    @Test
    void distinguishesRevokedRateLimitedAndUnknownOutcomes() throws Exception {
        FeishuOAuthClient revoked = server(400, "{\"code\":20064}");
        assertEquals(FeishuOAuthException.Kind.REVOKED,
                assertThrows(FeishuOAuthException.class,
                        () -> revoked.refresh("client", "secret", "old")).kind());
        server.stop(0);
        FeishuOAuthClient limited = server(429, "{}");
        assertEquals(FeishuOAuthException.Kind.TEMPORARY,
                assertThrows(FeishuOAuthException.class,
                        () -> limited.refresh("client", "secret", "old")).kind());
        server.stop(0);
        FeishuOAuthClient unknown = server(503, "{}");
        assertEquals(FeishuOAuthException.Kind.OUTCOME_UNKNOWN,
                assertThrows(FeishuOAuthException.class,
                        () -> unknown.refresh("client", "secret", "old")).kind());
    }

    private FeishuOAuthClient server(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/v3/token", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        return new FeishuOAuthClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/v3/token"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
