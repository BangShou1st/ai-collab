package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.net.URI;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpMcpClientFacade implements McpClientFacade {
    private final URI endpoint;
    private final String bearer;
    private final ObjectMapper json;
    private final McpEndpointPolicy endpoints;
    private final Duration defaultTimeout;
    private final int maxResponseBytes;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
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
        return new CallResult(result.path("isError").asBoolean(false), result.path("content"));
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
            endpoints.validate(endpoint);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (bearer != null && !bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            HttpResponse<InputStream> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location").orElseThrow();
                endpoints.validate(endpoint.resolve(location));
                throw new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403)
                response.body().close();
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "MCP 认证失败");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                response.body().close();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "MCP 服务调用失败");
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            String payload;
            try (InputStream responseBody = response.body()) {
                payload = readLimited(responseBody, maxResponseBytes);
            }
            if (payload == null || payload.isBlank()) return json.nullNode();
            if (payload.startsWith("event:") || payload.startsWith("data:")) {
                payload = payload.lines().filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).stripLeading()).reduce("", String::concat);
            }
            return json.readTree(payload);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new BusinessException(ErrorCode.AGENT_MCP_TIMEOUT);
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AGENT_MCP_TIMEOUT);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "MCP 服务暂不可用");
        }
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
