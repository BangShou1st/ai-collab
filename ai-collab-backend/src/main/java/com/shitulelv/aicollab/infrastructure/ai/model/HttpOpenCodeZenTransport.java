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

/** JDK HttpClient Zen transport. Policy (endpoint/identity) comes from {@link ProviderPresetRegistry}. Explicit DIRECT (NO_PROXY), Redirect.NEVER, runtime endpoint validation. */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {
    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final ProviderPresetRegistry registry;
    private final HttpClient production;
    private final HttpClient settings;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.production = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
        this.settings = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(45)).build();
    }
    HttpOpenCodeZenTransport(ObjectMapper mapper, OutboundEndpointPolicy endpoints, ProviderPresetRegistry registry, HttpClient production, HttpClient settings) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.registry = registry;
        this.production = production;
        this.settings = settings;
    }
    ProviderPresetRegistry.PresetPolicy policy() {
        return registry.require(ProviderPresetCode.OPENCODE_ZEN_FREE);
    }
    public static boolean isDirect(HttpClient client) { return client.proxy().map(s -> s == HttpClient.Builder.NO_PROXY).orElse(false); }
    public HttpClient productionClient() { return production; }
    @Override
    public JsonNode listModelsRaw(String apiKey, AiRequestMetadata metadata) {
        try {
            ProviderPresetRegistry.PresetPolicy pol = policy();
            URI uri = URI.create(registry.modelsEndpoint(pol));
            endpoints.requirePublicHttps(uri);
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(45)).GET()
                    .header("Accept", "application/json");
            OpenAiCompatibleModelAdapter.headersWithSession(apiKey, metadata, pol.userAgent()).forEach(b::header);
            HttpResponse<String> resp = settings.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonHttpModelClient.checkStatus(resp.statusCode(), resp.body(), mapper);
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
        // Use the same client envelope and SSE parser as inference, with a bounded
        // settings deadline. A completion probe does not certify tools or planning.
        try (var probe = new ProbeClient(mapper, endpoints, settings)) {
            var pol = policy();
            var now = java.time.OffsetDateTime.now();
            var config = new ModelConfiguration(null, null, pol.displayName(), pol.protocol(),
                    pol.baseUrl(), pol.completionPath(), null, model.strip(), true, 0, 256, pol.capabilities(), now, now);
            new OpenAiCompatibleModelAdapter(mapper, probe.client).completeStreamingSyncWithSession(config,
                    apiKey.strip(), new com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand(
                            "Synthetic connection probe.", "Return only {\"action\":\"finish\"}."), metadata, pol.userAgent());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR); }
    }
    public static ProxySelector directSelector() { return HttpClient.Builder.NO_PROXY; }
    public static Proxy directProxy() { return Proxy.NO_PROXY; }
    private static final class ProbeClient implements AutoCloseable {
        final JsonHttpModelClient client;
        ProbeClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient http) {
            client = new JsonHttpModelClient(mapper, endpoints, http, Duration.ofSeconds(45));
        }
        @Override public void close() { client.close(); }
    }
}
