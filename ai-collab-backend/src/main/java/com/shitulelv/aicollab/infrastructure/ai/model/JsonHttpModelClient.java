package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
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
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public JsonHttpModelClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode post(String url, Map<String, String> headers, JsonNode body) {
        try {
            HttpResponse<String> response = client.send(request(url, headers, body),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(response.statusCode());
            return parse(response.body());
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

    public void stream(
            String url,
            Map<String, String> headers,
            JsonNode body,
            BiConsumer<String, JsonNode> onEvent) {
        try {
            HttpResponse<java.io.InputStream> response = client.send(request(url, headers, body),
                    HttpResponse.BodyHandlers.ofInputStream());
            checkStatus(response.statusCode());
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(builder::header);
            return builder.build();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private static void checkStatus(int status) {
        if (status == 408 || status == 504) {
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        }
        if (status == 429) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }
}
