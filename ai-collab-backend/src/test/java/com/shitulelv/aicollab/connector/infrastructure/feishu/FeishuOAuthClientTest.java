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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuOAuthClientTest {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SUCCESS_BODY = """
            {"code":0,"access_token":"synthetic-access","refresh_token":"synthetic-refresh-new",
             "expires_in":1800,"refresh_token_expires_in":3600,"scope":"offline_access docx:document:readonly",
             "token_type":"Bearer"}
            """;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void exchangeSendsV2JsonBodyWithPkceVerifier() throws Exception {
        FeishuOAuthClient client = server(200, SUCCESS_BODY);
        client.exchange("synthetic-client", "synthetic-client-secret", "synthetic-code",
                "https://staging.example.com/api/v1/connector/oauth/callback", "synthetic-verifier");
        assertTrue(contentType.get().startsWith("application/json"));
        assertTrue(requestBody.get().contains("\"grant_type\":\"authorization_code\""));
        assertTrue(requestBody.get().contains("\"code\":\"synthetic-code\""));
        assertTrue(requestBody.get().contains("\"code_verifier\":\"synthetic-verifier\""));
        assertTrue(requestBody.get().contains("\"redirect_uri\":\"https://staging.example.com"));
        assertEquals(1, requests.get());
    }

    @Test
    void exchangeParsesRotatedTokensAndExpiry() throws Exception {
        FeishuOAuthClient client = server(200, SUCCESS_BODY);
        FeishuOAuthClient.Tokens result = client.exchange("client", "secret", "code",
                "https://staging.example.com/api/v1/connector/oauth/callback", "verifier");
        assertEquals("synthetic-access", result.accessToken());
        assertEquals("synthetic-refresh-new", result.refreshToken());
        assertEquals("offline_access docx:document:readonly", result.scope());
        assertEquals(NOW.plusSeconds(1800), result.accessExpiresAt());
        assertEquals(NOW.plusSeconds(3600), result.refreshExpiresAt());
        assertFalse(result.toString().contains("synthetic"));
    }

    @Test
    void refreshSendsV2JsonBodyAndNeverReplaysAfterLostResponse() throws Exception {
        FeishuOAuthClient client = server(200, SUCCESS_BODY);
        FeishuOAuthClient.Tokens result = client.refresh("synthetic-client", "synthetic-client-secret", "synthetic-old");
        assertEquals("synthetic-refresh-new", result.refreshToken());
        assertTrue(requestBody.get().contains("\"grant_type\":\"refresh_token\""));
        assertTrue(requestBody.get().contains("\"refresh_token\":\"synthetic-old\""));
        assertEquals(1, requests.get());
    }

    @Test
    void singleUseCodeFailureIsNotReplayed() throws Exception {
        FeishuOAuthClient client = server(400, "{\"code\":20065,\"error\":\"invalid_grant\","
                + "\"error_description\":\"The authorization code has been used.\"}");
        assertEquals(FeishuOAuthException.Kind.REAUTH_REQUIRED,
                assertThrows(FeishuOAuthException.class,
                        () -> client.exchange("client", "secret", "code", "https://cb", "verifier")).kind());
        assertEquals(1, requests.get());
    }

    @Test
    void mapsDocumentedErrorCodesToDistinctKinds() {
        record Case(int feishuCode, boolean refresh, FeishuOAuthException.Kind expected) {}
        List<Case> cases = List.of(
                new Case(20064, true, FeishuOAuthException.Kind.REVOKED),
                new Case(20026, true, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20037, true, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20073, true, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20003, false, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20004, false, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20065, false, FeishuOAuthException.Kind.REAUTH_REQUIRED),
                new Case(20071, false, FeishuOAuthException.Kind.CONFIGURATION),
                new Case(20074, true, FeishuOAuthException.Kind.CONFIGURATION),
                new Case(20002, true, FeishuOAuthException.Kind.CONFIGURATION));
        for (Case item : cases) {
            assertThrows(FeishuOAuthException.class, () -> {
                try {
                    FeishuOAuthClient client = server(400, "{\"code\":" + item.feishuCode() + "}");
                    if (item.refresh()) client.refresh("client", "secret", "old");
                    else client.exchange("client", "secret", "code", "https://cb", "verifier");
                } catch (FeishuOAuthException exception) {
                    assertEquals(item.expected(), exception.kind(), "code " + item.feishuCode());
                    throw exception;
                }
            });
            stop();
        }
    }

    @Test
    void distinguishesRateLimitedUnavailableAndUnknownOutcomes() throws Exception {
        assertEquals(FeishuOAuthException.Kind.TEMPORARY, outcome(429, "{}"));
        assertEquals(FeishuOAuthException.Kind.OUTCOME_UNKNOWN, outcome(503, "{}"));
        assertEquals(FeishuOAuthException.Kind.OUTCOME_UNKNOWN, outcome(500, "{\"code\":20050}"));
    }

    @Test
    void malformedSuccessPayloadIsNotTreatedAsVerifiedTokens() throws Exception {
        assertEquals(FeishuOAuthException.Kind.OUTCOME_UNKNOWN, outcome(200,
                "{\"code\":0,\"access_token\":\"only-access\"}"));
        // 交换阶段载荷不完整可安全判定为配置/响应无效；刷新阶段必须保持结果未知。
        FeishuOAuthClient client = server(200,
                "{\"code\":0,\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":0,"
                        + "\"refresh_token_expires_in\":1,\"scope\":\"offline_access\"}");
        assertEquals(FeishuOAuthException.Kind.CONFIGURATION,
                assertThrows(FeishuOAuthException.class,
                        () -> client.exchange("client", "secret", "code", "https://cb", "verifier")).kind());
    }

    private FeishuOAuthException.Kind outcome(int status, String body) throws Exception {
        try {
            FeishuOAuthClient client = server(status, body);
            client.refresh("client", "secret", "old");
            throw new AssertionError("expected FeishuOAuthException");
        } catch (FeishuOAuthException exception) {
            return exception.kind();
        }
    }

    private FeishuOAuthClient server(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/authen/v2/oauth/token", exchange -> {
            requests.incrementAndGet();
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        return new FeishuOAuthClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis/authen/v2/oauth/token"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
