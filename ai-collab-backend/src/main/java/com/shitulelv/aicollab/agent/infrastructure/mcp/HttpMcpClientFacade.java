package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpMcpClientFacade implements McpClientFacade {
    private final URI endpoint;
    private final String bearer;
    private final ObjectMapper json;
    private final McpEndpointPolicy endpoints;
    private final Duration defaultTimeout;
    private final int maxResponseBytes;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;

    public HttpMcpClientFacade(URI endpoint, String bearer, int timeoutMs, int maxResponseBytes,
                               ObjectMapper json, McpEndpointPolicy endpoints) {
        this.endpoint = endpoints.validate(endpoint);
        this.bearer = bearer;
        this.json = json;
        this.endpoints = endpoints;
        this.defaultTimeout = Duration.ofMillis(timeoutMs);
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public ServerInfo initialize() {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        params.set("capabilities", json.createObjectNode());
        ObjectNode client = json.createObjectNode();
        client.put("name", "ai-collab"); client.put("version", "2.0");
        params.set("clientInfo", client);
        JsonNode result = rpc("initialize", params, defaultTimeout);
        notify("notifications/initialized", json.createObjectNode(), defaultTimeout);
        JsonNode info = result.path("serverInfo");
        return new ServerInfo(info.path("name").asText("unknown"), info.path("version").asText("unknown"));
    }

    @Override
    public List<DiscoveredTool> listTools() {
        JsonNode result = rpc("tools/list", json.createObjectNode(), defaultTimeout);
        return stream(result.path("tools")).map(tool -> new DiscoveredTool(
                tool.path("name").asText(), tool.path("description").asText(""),
                tool.path("inputSchema"), tool.get("outputSchema"), tool.get("annotations"))).toList();
    }

    @Override
    public List<DiscoveredResource> listResources() {
        JsonNode result = rpc("resources/list", json.createObjectNode(), defaultTimeout);
        return stream(result.path("resources")).map(resource -> new DiscoveredResource(
                resource.path("uri").asText(), resource.path("name").asText(""),
                resource.path("description").asText(""), resource.path("mimeType").asText(""))).toList();
    }

    @Override
    public CallResult callTool(String serverToolName, JsonNode arguments, Duration timeout) {
        ObjectNode params = json.createObjectNode();
        params.put("name", serverToolName); params.set("arguments", arguments);
        JsonNode result = rpc("tools/call", params, timeout);
        return new CallResult(result.path("isError").asBoolean(false), result.path("content"),
                result.get("structuredContent"));
    }

    @Override
    public ReadResourceResult readResource(String uri, Duration timeout) {
        ObjectNode params = json.createObjectNode(); params.put("uri", uri);
        JsonNode result = rpc("resources/read", params, timeout);
        return new ReadResourceResult(false, result.path("contents"));
    }

    private JsonNode rpc(String method, JsonNode params, Duration timeout) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0"); request.put("id", ids.incrementAndGet());
        request.put("method", method); request.set("params", params);
        JsonNode response = send(request, timeout);
        if (response.has("error")) {
            int code = response.path("error").path("code").asInt();
            if (code == -32601) throw new BusinessException(ErrorCode.AGENT_MCP_SCHEMA_CHANGED);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "MCP 服务调用失败");
        }
        return response.path("result");
    }

    private void notify(String method, JsonNode params, Duration timeout) {
        ObjectNode request = json.createObjectNode(); request.put("jsonrpc", "2.0");
        request.put("method", method); request.set("params", params); send(request, timeout);
    }

    private JsonNode send(JsonNode body, Duration timeout) {
        try {
            McpEndpointPolicy.PinnedEndpoint pinned = endpoints.validateAndPin(endpoint);
            OkHttpClient client = httpClient(pinned, timeout);
            Request.Builder builder = new Request.Builder()
                    .url(HttpUrl.get(endpoint))
                    .post(RequestBody.create(json.writeValueAsString(body), MediaType.get("application/json")))
                    .header("Accept", "application/json, text/event-stream");
            if (bearer != null && !bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            try (Response response = client.newCall(builder.build()).execute()) {
                int status = response.code();
                if (status >= 300 && status < 400) {
                    String location = response.header("Location");
                    if (location != null) {
                        endpoints.validate(endpoint.resolve(location));
                    }
                    throw new BusinessException(ErrorCode.AGENT_MCP_REDIRECT_BLOCKED);
                }
                if (status == 401 || status == 403) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "MCP 认证失败");
                }
                if (status == 429) {
                    throw new BusinessException(ErrorCode.AGENT_MCP_RATE_LIMITED);
                }
                if (status < 200 || status >= 300) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "MCP 服务调用失败");
                }
                String session = response.header("Mcp-Session-Id");
                if (session != null) {
                    sessionId = session;
                }
                String payload;
                ResponseBody responseBody = response.body();
                try (InputStream stream = responseBody == null
                        ? InputStream.nullInputStream() : responseBody.byteStream()) {
                    payload = readLimited(stream, maxResponseBytes);
                }
                if (payload == null || payload.isBlank()) return json.nullNode();
                if (payload.startsWith("event:") || payload.startsWith("data:")) {
                    payload = payload.lines().filter(line -> line.startsWith("data:"))
                            .map(line -> line.substring(5).stripLeading()).reduce("", String::concat);
                }
                return json.readTree(payload);
            }
        } catch (SocketTimeoutException exception) {
            throw new BusinessException(ErrorCode.AGENT_MCP_TIMEOUT);
        } catch (InterruptedIOException exception) {
            throw new BusinessException(ErrorCode.AGENT_MCP_TIMEOUT);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "MCP 服务暂不可用");
        }
    }

    static Dns pinnedDns(McpEndpointPolicy.PinnedEndpoint pinned) {
        List<InetAddress> addresses = pinned.addresses();
        String expectedHost = pinned.endpoint().getHost();
        return host -> {
            if (host == null || expectedHost == null || !host.equalsIgnoreCase(expectedHost)) {
                throw new UnknownHostException(host);
            }
            return addresses;
        };
    }

    private static OkHttpClient httpClient(McpEndpointPolicy.PinnedEndpoint pinned, Duration timeout) {
        return new OkHttpClient.Builder()
                .dns(pinnedDns(pinned))
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    static String readLimited(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_RESULT_TOO_LARGE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode node) {
        if (node == null || !node.isArray()) return java.util.stream.Stream.empty();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false);
    }

    @Override public void close() { }
}
