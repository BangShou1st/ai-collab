package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
public class JsonHttpModelClient {
    private static final Logger log = LoggerFactory.getLogger(JsonHttpModelClient.class);
    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy endpoints;
    private final HttpClient client;
    private final Duration timeout;
    private final java.util.concurrent.ExecutorService streams = new java.util.concurrent.ThreadPoolExecutor(
            16, 16, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
            r -> { Thread t = new Thread(r, "model-stream"); t.setDaemon(true); return t; });

    public JsonHttpModelClient(ObjectMapper mapper) {
        this(mapper, new OutboundEndpointPolicy());
    }

    @Autowired
    public JsonHttpModelClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints) {
        this(mapper, endpoints, HttpClient.newBuilder()
                // 连接超时保持短（15 秒）：连不上应快速失败，不占满单次请求预算
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public JsonHttpModelClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient client) {
        this(mapper, endpoints, client, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 单次模型请求的默认传输超时：10 分钟（设计 3.1）。
     * 更长的研究会话通过多次请求推进，不靠一次请求跑到超时上限；
     * 实际出站还受本运行剩余活跃时长与 {@code AiRequestDeadline} 取更小的约束。
     */
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    /** 连接超时（不是请求超时）：15 秒。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    public JsonHttpModelClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient client, Duration timeout) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.client = client;
        this.timeout = timeout;
    }

    @jakarta.annotation.PreDestroy
    public void close() { streams.shutdownNow(); }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body) {
        return post(url, headers, body, 1);
    }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body, int maxAttempts) {
        BusinessException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                log.debug("Model API request: model={}, stream={}", body.path("model").asText(), body.path("stream").asBoolean());
                HttpResponse<String> response = sendBounded(request(url, headers, body));
                log.debug("Model API response status: {}", response.statusCode());
                if (response.statusCode() == 408 || response.statusCode() == 504) {
                    // 超时错误可重试
                    lastError = new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                    if (attempt < maxAttempts - 1) {
                        try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                        }
                        continue;
                    }
                }
                checkStatus(response.statusCode(), response.body(), mapper);
                return parse(response.body());
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT
                        && attempt < maxAttempts - 1) {
                    lastError = exception;
                    try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                    }
                    continue;
                }
                throw exception;
            } catch (java.net.http.HttpTimeoutException exception) {
                lastError = new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                if (attempt < maxAttempts - 1) {
                    try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                    }
                    continue;
                }
                throw lastError;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            } catch (Exception exception) {
                log.error("Model API call failed: {}", exception.getMessage(), exception);
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
            }
        }
        throw lastError != null ? lastError : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
    }

    public void stream(
            String url,
            Map<String, String> headers,
            JsonNode body,
            BiConsumer<String, JsonNode> onEvent) {
        java.util.concurrent.atomic.AtomicReference<java.io.InputStream> input = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.Future<?> task;
        try { task = streams.submit(() -> readStream(url, headers, body, onEvent, input)); }
        catch (java.util.concurrent.RejectedExecutionException busy) { throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE); }
        try {
            task.get(AiRequestDeadline.timeoutMillis(timeout), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException expired) {
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        } finally {
            task.cancel(true);
            java.io.InputStream stream = input.get();
            if (stream != null) try { stream.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private void readStream(String url, Map<String, String> headers, JsonNode body,
            BiConsumer<String, JsonNode> onEvent,
            java.util.concurrent.atomic.AtomicReference<java.io.InputStream> input) {
        try {
            log.debug("Model API stream request: model={}", body.path("model").asText());
            HttpResponse<java.io.InputStream> response = client.send(request(url, headers, body),
                    HttpResponse.BodyHandlers.ofInputStream());
            input.set(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (var errorBody = response.body()) {
                    checkStatus(response.statusCode(), new String(errorBody.readNBytes(16 * 1024), StandardCharsets.UTF_8), mapper);
                }
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                checkStatus(response.statusCode(), null, mapper);
                if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                        .startsWith("text/event-stream")) throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                                "PROVIDER_HTTP / UNEXPECTED_CONTENT_TYPE / status=" + response.statusCode(), null, null);
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                long received=0;
                while ((line = reader.readLine()) != null) {
                    received+=line.length();
                    if (received>8_000_000) throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"SSE 响应超过容量限制");
                    if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            String payload = data.toString();
                            if ("[DONE]".equals(payload)) { onEvent.accept("done", null); return; }
                            JsonNode value = parse(payload);
                            if (value == null || !value.isObject() || value.hasNonNull("error"))
                                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                                        "PROVIDER_STREAM / " + (value != null && value.hasNonNull("error") ? "ERROR_ENVELOPE" : "INVALID_EVENT_SHAPE"), null, null);
                            onEvent.accept(event, value);
                        }
                        data.setLength(0);
                        event = "message";
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                        if (data.length() > 2_000_000) throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
                    }
                }
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, "PROVIDER_STREAM / MISSING_DONE", null, null);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
    }
    private HttpResponse<String> sendBounded(HttpRequest request) throws java.io.IOException, InterruptedException {
        var pending=streams.submit(() -> client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        try { return pending.get(AiRequestDeadline.timeoutMillis(timeout),java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException elapsed) { throw new java.net.http.HttpTimeoutException("模型正文读取超时"); }
        catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof java.io.IOException io) throw io;
            if (failed.getCause() instanceof InterruptedException interrupted) throw interrupted;
            throw new java.io.IOException("模型响应读取失败",failed.getCause());
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private HttpRequest request(String url, Map<String, String> headers, JsonNode body) {
        try {
            URI endpoint = URI.create(url.strip());
            endpoints.requirePublicHttps(endpoint);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(builder::header);
            return builder.build();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    static void checkStatus(int status, String responseBody, ObjectMapper mapper) {
        if (status == 408 || status == 504) {
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        }
        if (status == 429) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
        }
        if (status == 403) {
            // Only expose a recognized error classification, never echo server bodies
            // that may contain credentials or prompt data. Unknown 403 is not a key verdict.
            boolean freeTier = false;
            try {
                JsonNode root = mapper.readTree(responseBody == null ? "{}" : responseBody);
                for (JsonNode node : java.util.List.of(root, root.path("error")))
                    for (String field : java.util.List.of("name", "type", "code"))
                        if ("FreeTierError".equals(node.path(field).asText())) freeTier = true;
                if ("FreeTierError".equals(root.path("error").asText())) freeTier = true;
            } catch (Exception ignored) { /* Not a recognized structured error. */ }
            log.warn("Model API rejected request: HTTP 403, classification={}", freeTier ? "FreeTierError" : "UNKNOWN");
            throw new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED, freeTier
                    ? "模型服务拒绝免费额度请求 (HTTP 403 / FreeTierError)，请检查客户端请求方式与访问权限"
                    : "模型服务拒绝请求 (HTTP 403)，具体原因未确认，请检查访问权限与请求方式");
        }
        if (status == 401) {
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID,
                    "模型 API Key 无效或无权访问 (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            log.warn("Model API returned HTTP {}", status);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "模型服务返回 HTTP " + status);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
