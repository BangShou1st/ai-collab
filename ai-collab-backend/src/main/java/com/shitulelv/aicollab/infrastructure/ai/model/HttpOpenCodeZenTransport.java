package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** JDK HttpClient Zen transport. Explicit DIRECT (NO_PROXY), Redirect.NEVER, runtime endpoint validation. */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {
    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final HttpClient production;
    private final HttpClient settings;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.production = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        this.settings = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(45)).build();
    }
    HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient production, HttpClient settings) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.production = production;
        this.settings = settings;
    }
    public static boolean isDirect(HttpClient client) { return client.proxy().map(s -> s == HttpClient.Builder.NO_PROXY).orElse(false); }
    public HttpClient productionClient() { return production; }
    @Override
    public JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) {
        try {
            URI uri = URI.create(BASE_URL + MODELS_PATH);
            endpoints.requirePublicHttps(uri);
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(45)).GET()
                    .header("Accept", "application/json").header("User-Agent", USER_AGENT);
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey.strip());
            if (metadata != null) b.header(SESSION_HEADER, metadata.correlationSessionId());
            HttpResponse<String> resp = settings.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(resp.statusCode(), resp.body());
            return mapper.readTree(resp.body());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR); }
    }
    @Override
    public List<String> listFreeModels(String apiKey, AiRequestMetadata metadata) {
        JsonNode root = listModelsRaw(apiKey, metadata);
        JsonNode data = root.path("data");
        List<String> out = new ArrayList<>();
        if (data.isArray()) for (JsonNode n : data) {
            String id = n.path("id").asText(null);
            if (id != null && id.endsWith("-free")) out.add(id);
        }
        return out;
    }
    @Override
    public void validateCredential(String apiKey, String model, AiRequestMetadata metadata) {
        if (apiKey == null || apiKey.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key required");
        if (model == null || model.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        try {
            ObjectMapper m = mapper;
            var body = m.createObjectNode();
            body.put("model", model.strip());
            body.put("max_tokens", 16);
            var msgs = body.putArray("messages");
            msgs.addObject().put("role", "user").put("content", "Return only {\"action\":\"finish\"}.");
            URI uri = URI.create(BASE_URL + COMPLETIONS_PATH);
            endpoints.requirePublicHttps(uri);
            HttpRequest req = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT).header("Authorization", "Bearer " + apiKey.strip())
                    .header(SESSION_HEADER, metadata.correlationSessionId())
                    .POST(HttpRequest.BodyPublishers.ofString(m.writeValueAsString(body))).build();
            HttpResponse<String> resp = settings.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(resp.statusCode(), resp.body());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR); }
    }
    public static ProxySelector directSelector() { return HttpClient.Builder.NO_PROXY; }
    public static Proxy directProxy() { return Proxy.NO_PROXY; }
    private static void check(int status, String body) {
        if (status == 401 || status == 403) throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
        if (status == 429) throw new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
        if (status < 200 || status >= 300) throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Zen HTTP " + status);
    }
}
