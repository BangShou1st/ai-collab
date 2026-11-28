package com.shitulelv.aicollab.knowledge.infrastructure.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import com.shitulelv.aicollab.knowledge.infrastructure.remote.KnowledgeQaModels.Event;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * P3 契约 §3.3：调用 worker {@code POST /v1/knowledge/qa:stream} 的流式客户端（Java 中继侧）。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>每次尝试签发 {@code knowledgeQaToken}（fail closed：拿不到令牌不发请求）；</li>
 *   <li>503/5xx 在**未转发任何 token 前**重试 1 次；重试必须终止上一条流（关闭连接实现）；
 *       已开始转发的流不重试（生成非幂等，前端表现与"连接断开重试"一致）；</li>
 *   <li>非 2xx 映射：400 → VALIDATION_ERROR；504 → AI_MODEL_TIMEOUT；其余 →
 *       AI_PROVIDER_UNAVAILABLE（§3.3 错误码表）；</li>
 *   <li>事件解析：{@code event:} + {@code data:} 帧；解析失败视为 5xx 语义（INTERNAL_ERROR）。</li>
 * </ul>
 *
 * <p>日志只记 requestId、outcome 与耗时，不记录问题、回答与令牌。</p>
 */
@Component
public class KnowledgeQaStreamClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaStreamClient.class);

    static final String QA_STREAM_PATH = "/v1/knowledge/qa:stream";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(10);

    private final KnowledgeSearchProperties properties;
    private final InternalTokenProvider tokens;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public KnowledgeQaStreamClient(KnowledgeSearchProperties properties, InternalTokenProvider tokens,
            ObjectMapper objectMapper) {
        this(properties, tokens, objectMapper, OutboundHttpClients.builder()
                .connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build());
    }

    KnowledgeQaStreamClient(KnowledgeSearchProperties properties, InternalTokenProvider tokens,
            ObjectMapper objectMapper, HttpClient http) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * 打开流并逐事件回调。回调返回 {@code false} 表示消费方要求中止（断连/终止），客户端
     * 关闭连接并正常返回（不重试）。传输失败、非 2xx、错误事件都以 {@link BusinessException}
     * 抛出；成功以 finalize 事件结束。
     *
     * @throws BusinessException 终止性失败（已按 §3.3 映射错误码）
     */
    public KnowledgeQaModels.Finalize stream(KnowledgeQaModels.Request request, EventListener listener) {
        BusinessException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Attempt outcome = attempt(request, listener);
            if (outcome.finalizePayload() != null) {
                return outcome.finalizePayload();
            }
            last = outcome.failure();
            if (!outcome.retryable()) {
                break;
            }
        }
        throw last;
    }

    private Attempt attempt(KnowledgeQaModels.Request request, EventListener listener) {
        String token;
        try {
            token = tokens.knowledgeQaToken(request.userId(), request.projectId());
        } catch (RuntimeException unsupported) {
            log.error("knowledge qa token unavailable (fail closed)");
            return Attempt.failure(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE), false);
        }
        if (token == null || token.isBlank()) {
            return Attempt.failure(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE), false);
        }
        String body;
        try {
            body = json.writeValueAsString(request);
        } catch (IOException serialization) {
            return Attempt.failure(new BusinessException(ErrorCode.INTERNAL_ERROR), false);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + QA_STREAM_PATH))
                .timeout(HEADERS_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException timeout) {
            return Attempt.failure(new BusinessException(ErrorCode.AI_MODEL_TIMEOUT), retryable());
        } catch (IOException | InterruptedException transport) {
            if (transport instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Attempt.failure(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE), retryable());
        }
        int status = response.statusCode();
        if (status == 504) {
            closeQuietly(response);
            return Attempt.failure(new BusinessException(ErrorCode.AI_MODEL_TIMEOUT), false);
        }
        if (status < 200 || status >= 300) {
            closeQuietly(response);
            ErrorCode mapped = status == 400 ? ErrorCode.VALIDATION_ERROR : ErrorCode.AI_PROVIDER_UNAVAILABLE;
            return Attempt.failure(
                    new BusinessException(mapped, "问答生成不可用（worker " + status + "）"),
                    (status >= 500 || status == 429) && retryable());
        }
        try {
            return consume(request, listener, response, started);
        } finally {
            closeQuietly(response);
        }
    }

    private Attempt consume(KnowledgeQaModels.Request request, EventListener listener,
            HttpResponse<InputStream> response, long started) {
        KnowledgeQaModels.Finalize finalize = null;
        boolean forwarded = false;
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName == null && data.isEmpty()) {
                        continue;
                    }
                    Parsed parsed = parseFrame(request.requestId(), eventName, data.toString());
                    eventName = null;
                    data.setLength(0);
                    if (parsed.errorCode() != null) {
                        return Attempt.failure(mappedWorkerError(parsed.errorCode(), parsed.message), false);
                    }
                    if (parsed.finalizePayload() != null) {
                        finalize = parsed.finalizePayload();
                        break;
                    }
                    if (!listener.onEvent(parsed.event())) {
                        // 消费方中止（断连/终止）：正常返回，不重试、无 finalize。
                        return Attempt.failure(
                                new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, "流已被消费方中止"), false);
                    }
                    forwarded = true;
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
                // 忽略注释行（: heartbeat）与 id 行（P3 无断线续传，Q8=a）
            }
        } catch (IOException transport) {
            BusinessException failure = new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, "问答流中断");
            return Attempt.failure(failure, retryable() && !forwarded);
        }
        if (finalize == null) {
            // 流在 finalize 前结束（worker 半途断开）——按不可重试的超时处理，与断线一致。
            return Attempt.failure(new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, "问答流未完成"),
                    retryable() && !forwarded);
        }
        log.info("knowledge qa stream ok requestId={} elapsedMs={}",
                request.requestId(), (System.nanoTime() - started) / 1_000_000);
        return Attempt.success(finalize);
    }

    private record Parsed(Event event, KnowledgeQaModels.Finalize finalizePayload, String errorCode, String message) {
    }

    private Parsed parseFrame(UUID requestId, String eventName, String data) {
        try {
            return switch (eventName == null ? "" : eventName) {
                case "token" -> new Parsed(Event.token(json.readValue(data,
                        KnowledgeQaModels.TokenPayload.class).text()), null, null, null);
                case "finalize" -> new Parsed(null,
                        json.readValue(data, KnowledgeQaModels.Finalize.class), null, null);
                case "error" -> {
                    KnowledgeQaModels.WorkerError error =
                            json.readValue(data, KnowledgeQaModels.WorkerError.class);
                    yield new Parsed(Event.errorEvent(error), null, error.code(), error.message());
                }
                default -> new Parsed(null, null, "INTERNAL_ERROR", "未知事件类型");
            };
        } catch (IOException malformed) {
            log.warn("knowledge qa stream malformed frame requestId={}", requestId);
            return new Parsed(null, null, "INTERNAL_ERROR", "问答流事件解析失败");
        }
    }

    private static BusinessException mappedWorkerError(String code, String message) {
        // §3.3：worker 错误码沿用契约命名；Java 侧映射终止语义，文案透传（如 CONTENT_BLOCKED
        // 的统一拦截文案与前端展示一致）。
        String safeMessage = message == null || message.isBlank() ? null : message;
        return switch (code == null ? "" : code) {
            case "QA_DEADLINE_EXCEEDED" -> safeMessage == null
                    ? new BusinessException(ErrorCode.AI_MODEL_TIMEOUT)
                    : new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, safeMessage);
            case "VALIDATION_ERROR" -> safeMessage == null
                    ? new BusinessException(ErrorCode.VALIDATION_ERROR)
                    : new BusinessException(ErrorCode.VALIDATION_ERROR, safeMessage);
            case "CONTENT_BLOCKED" -> safeMessage == null
                    ? new BusinessException(ErrorCode.AI_PROVIDER_ERROR)
                    : new BusinessException(ErrorCode.AI_PROVIDER_ERROR, safeMessage);
            default -> safeMessage == null
                    ? new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE)
                    : new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, safeMessage);
        };
    }

    private static boolean retryable() {
        // 生成非幂等：只在"未转发任何 token"的前提下由外层重试一次（未转发前恒可重试）。
        return true;
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try (InputStream stream = response.body()) {
            // 关闭即终止在途流（§3.3：重试必须终止上一条流）。
        } catch (IOException ignored) {
            // 关闭失败不影响结果
        }
    }

    /** 事件回调：返回 {@code false} 表示消费方要求中止。 */
    public interface EventListener {
        boolean onEvent(Event event);
    }

    private record Attempt(KnowledgeQaModels.Finalize finalizePayload, BusinessException failure, boolean retryable) {
        static Attempt success(KnowledgeQaModels.Finalize finalize) {
            return new Attempt(finalize, null, false);
        }

        static Attempt failure(BusinessException failure, boolean retryable) {
            return new Attempt(null, failure, retryable);
        }
    }
}
