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

    public JsonHttpModelClient(ObjectMapper mapper) {
        this(mapper, new OutboundEndpointPolicy());
    }

    @Autowired
    public JsonHttpModelClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints) {
        this(mapper, endpoints, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public JsonHttpModelClient(ObjectMapper mapper, OutboundEndpointPolicy endpoints, HttpClient client) {
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.client = client;
    }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body) {
        return post(url, headers, body, 2);
    }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body, int maxAttempts) {
        BusinessException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                log.debug("Model API request to {}: {}", url, truncate(body.toString(), 500));
                HttpResponse<String> response = client.send(request(url, headers, body),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                log.debug("Model API response status: {}, body: {}",
                        response.statusCode(), truncate(response.body(), 500));
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
                checkStatus(response.statusCode(), response.body());
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
        try {
            log.debug("Model API stream request to {}: {}", url, truncate(body.toString(), 500));
            HttpResponse<java.io.InputStream> response = client.send(request(url, headers, body),
                    HttpResponse.BodyHandlers.ofInputStream());
            checkStatus(response.statusCode(), null);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = "message";
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).strip();
                        if ("[DONE]".equals(data)) {
                            onEvent.accept("done", null);
                            return;
                        }
                        if (!data.isBlank()) {
                            onEvent.accept(event, parse(data));
                        }
                        event = "message";
                    }
                }
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

    private HttpRequest request(String url, Map<String, String> headers, JsonNode body) {
        try {
            URI endpoint = URI.create(url.strip());
            endpoints.requirePublicHttps(endpoint);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMinutes(3))
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

    private static void checkStatus(int status, String responseBody) {
        if (status == 408 || status == 504) {
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        }
        if (status == 429) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
        }
        if (status == 401 || status == 403) {
            throw new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID,
                    "模型 API Key 无效或无权访问 (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            // 记录响应体以便调试
            String detail = responseBody != null && !responseBody.isBlank()
                    ? " (响应: " + truncate(responseBody, 200) + ")"
                    : "";
            log.warn("Model API returned HTTP {}: {}", status, detail);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "模型服务返回 HTTP " + status + detail);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
