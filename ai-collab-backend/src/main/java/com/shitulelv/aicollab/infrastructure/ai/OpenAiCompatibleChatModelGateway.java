package com.shitulelv.aicollab.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
public class OpenAiCompatibleChatModelGateway implements ChatModelGateway {
    private static final int MAX_PROVIDER_CODE_POINTS = 80;
    private static final int MAX_MODEL_CODE_POINTS = 120;
    private static final int MAX_BASE_URL_CODE_POINTS = 2048;
    private static final int MAX_PATH_CODE_POINTS = 512;
    private static final int MAX_OUTPUT_TOKENS = 32_768;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(5);

    private final ChatModelProperties properties;
    private final RestClient restClient;
    private final boolean retryTransientFailures;

    @Autowired
    public OpenAiCompatibleChatModelGateway(ChatModelProperties properties) {
        this(properties, true);
    }

    public OpenAiCompatibleChatModelGateway(ChatModelProperties properties, boolean retryTransientFailures) {
        this.properties = properties;
        this.retryTransientFailures = retryTransientFailures;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(boundedDuration(
                properties.connectTimeout(), DEFAULT_CONNECT_TIMEOUT, MAX_CONNECT_TIMEOUT));
        factory.setReadTimeout(boundedDuration(
                properties.readTimeout(), DEFAULT_READ_TIMEOUT, MAX_READ_TIMEOUT));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionCommand command) {
        validateConfiguration();
        long started = System.nanoTime();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ResponseFormat responseFormat = command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                        ? new ResponseFormat("json_object") : null;
                ChatResponse response = restClient.post()
                        .uri(join(properties.baseUrl(), properties.path()))
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .body(new ChatRequest(
                                properties.model().strip(),
                                List.of(
                                        new ChatMessage("system", command.systemPrompt()),
                                        new ChatMessage("user", command.userPrompt())),
                                properties.temperature(),
                                properties.maxOutputTokens(),
                                false,
                                responseFormat))
                        .retrieve()
                        .body(ChatResponse.class);
                String content = validateResponse(response);
                checkFinishReason(response);
                Usage usage = response.usage();
                return new ChatCompletionResult(
                        content,
                        properties.provider().strip(),
                        responseModel(response),
                        usage == null ? null : usage.promptTokens(),
                        usage == null ? null : usage.completionTokens(),
                        elapsedMs(started));
            } catch (HttpClientErrorException exception) {
                int status = exception.getStatusCode().value();
                if (status == 429) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
                }
                if (status == 408 && attempt == 0 && retryTransientFailures) {
                    continue;
                }
                if (status == 408) {
                    throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                }
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
            } catch (HttpServerErrorException exception) {
                int status = exception.getStatusCode().value();
                if (status == 504 && attempt == 0 && retryTransientFailures) {
                    continue;
                }
                if (status == 504) {
                    throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                }
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
            } catch (ResourceAccessException exception) {
                if (causedByTimeout(exception)) {
                    if (attempt == 0 && retryTransientFailures) {
                        continue;
                    }
                    throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                }
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
            } catch (BusinessException exception) {
                throw exception;
            } catch (RestClientException | IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
            }
        }
        throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
    }

    private void validateConfiguration() {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (blank(properties.provider())
                || codePointLength(properties.provider()) > MAX_PROVIDER_CODE_POINTS
                || blank(properties.baseUrl())
                || codePointLength(properties.baseUrl()) > MAX_BASE_URL_CODE_POINTS
                || blank(properties.path())
                || codePointLength(properties.path()) > MAX_PATH_CODE_POINTS
                || blank(properties.apiKey())
                || blank(properties.model())
                || codePointLength(properties.model()) > MAX_MODEL_CODE_POINTS
                || !validEndpoint(properties.baseUrl(), properties.path())
                || !validDuration(properties.connectTimeout(), MAX_CONNECT_TIMEOUT)
                || !validDuration(properties.readTimeout(), MAX_READ_TIMEOUT)
                || !Double.isFinite(properties.temperature())
                || properties.temperature() < 0
                || properties.temperature() > 2
                || properties.maxOutputTokens() < 1
                || properties.maxOutputTokens() > MAX_OUTPUT_TOKENS) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private String responseModel(ChatResponse response) {
        String value = response.model() == null || response.model().isBlank()
                ? properties.model().strip()
                : response.model().strip();
        if (codePointLength(value) > MAX_MODEL_CODE_POINTS) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        return value;
    }

    private static String validateResponse(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        ChatChoice first = response.choices().getFirst();
        if (first == null || first.message() == null
                || first.message().content() == null || first.message().content().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        if (response.usage() != null
                && (negative(response.usage().promptTokens())
                    || negative(response.usage().completionTokens()))) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        return first.message().content().strip();
    }

    private static void checkFinishReason(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) return;
        ChatChoice first = response.choices().getFirst();
        if (first != null && "length".equals(first.finish_reason())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private static boolean causedByTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Duration boundedDuration(
            Duration value, Duration fallback, Duration maximum) {
        return validDuration(value, maximum) ? value : fallback;
    }

    private static boolean validDuration(Duration value, Duration maximum) {
        return value != null
                && !value.isZero()
                && !value.isNegative()
                && value.compareTo(maximum) <= 0;
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean validEndpoint(String baseUrl, String path) {
        try {
            URI base = URI.create(baseUrl);
            URI pathUri = URI.create(path);
            URI endpoint = URI.create(join(baseUrl, path));
            return base.isAbsolute()
                    && ("http".equalsIgnoreCase(base.getScheme())
                        || "https".equalsIgnoreCase(base.getScheme()))
                    && base.getHost() != null
                    && base.getUserInfo() == null
                    && base.getQuery() == null
                    && base.getFragment() == null
                    && pathUri.getScheme() == null
                    && pathUri.getRawAuthority() == null
                    && pathUri.getQuery() == null
                    && pathUri.getFragment() == null
                    && !pathUri.getPath().isBlank()
                    && endpoint.isAbsolute()
                    && endpoint.getHost() != null
                    && endpoint.getUserInfo() == null
                    && endpoint.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String join(String base, String path) {
        return base.endsWith("/") && path.startsWith("/") ? base + path.substring(1)
                : !base.endsWith("/") && !path.startsWith("/") ? base + "/" + path : base + path;
    }

    private record ChatRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            int max_tokens,
            boolean stream,
            @JsonInclude(JsonInclude.Include.NON_NULL) ResponseFormat response_format) {
    }

    private record ResponseFormat(String type) {
    }

    private record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(List<ChatChoice> choices, String model, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatChoice(ChatMessage message, String finish_reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(Integer prompt_tokens, Integer completion_tokens) {
        Integer promptTokens() {
            return prompt_tokens;
        }

        Integer completionTokens() {
            return completion_tokens;
        }
    }
}
