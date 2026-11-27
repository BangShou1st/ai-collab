package com.shitulelv.aicollab.retrieval.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.net.OutboundHttpClients;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Java → Python {@code POST /v1/knowledge/search}（P2 契约 §4.4、§12.4 P2-R2）。
 *
 * <ul>
 *   <li><b>前置校验</b>：{@code visibleDocumentIds} 超过 5000 直接 {@code VALIDATION_ERROR}（400），不截断、不发请求；
 *       空集合直接返回空结果，不发请求；其余请求约束（queries 1–5 且恰好一条 PRIMARY、topK、candidatePool）
 *       也在发请求前校验，违规按 400 处理；</li>
 *   <li><b>状态码</b>：只按 HTTP 状态分支。400/401/403 不重试；409 → {@code KNOWLEDGE_INDEX_UNAVAILABLE}；
 *       503 与其他 5xx、传输失败最多重试 1 次，仍失败 → {@code KNOWLEDGE_SEARCH_UNAVAILABLE}；
 *       504 → {@code KNOWLEDGE_SEARCH_TIMEOUT}，不重试。<b>任何失败都不回落到 Java 检索</b>；</li>
 *   <li><b>令牌</b>：每次 HTTP 尝试前重新签发检索令牌（uid/pid 与请求体一致）；</li>
 *   <li><b>响应</b>：只做结构校验（requestId 回显、hits 非 null、条数上限 24、scores.final ∈ [0,1]），
 *       引用身份复核由 {@code SearchHitVerifier} 负责。日志不记查询原文、正文与令牌。</li>
 * </ul>
 */
@Component
public class KnowledgeSearchClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchClient.class);

    static final String SEARCH_PATH = "/v1/knowledge/search";
    public static final int MAX_VISIBLE_DOCUMENTS = 5000;
    public static final int MAX_RESPONSE_HITS = 24;
    private static final int MAX_QUERIES = 5;
    private static final int MAX_QUERY_CHARS = 2000;
    private static final int MAX_HEADING_FILTER_CHARS = 300;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CANDIDATE_POOL = 50;
    private static final int MAX_DEADLINE_MS = 600_000;
    private static final Set<String> VALID_STRATEGIES = Set.of(
            "DENSE", "HYBRID", "MULTI_QUERY_HYBRID", "DENSE_WITH_BOUNDED_FALLBACK");
    private static final long MAX_RESPONSE_BYTES = 8L * 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_GRACE = Duration.ofSeconds(2);

    private final KnowledgeSearchProperties properties;
    private final InternalTokenProvider tokens;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public KnowledgeSearchClient(KnowledgeSearchProperties properties, InternalTokenProvider tokens,
            ObjectMapper objectMapper) {
        this(properties, tokens, objectMapper, OutboundHttpClients.builder()
                .connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build());
    }

    KnowledgeSearchClient(KnowledgeSearchProperties properties, InternalTokenProvider tokens,
            ObjectMapper objectMapper, HttpClient http) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = Objects.requireNonNull(http, "http");
    }

    public KnowledgeSearchModels.Response search(KnowledgeSearchModels.Request request) {
        validate(request);
        if (request.visibleDocumentIds().isEmpty()) {
            // 契约 §4.4：空集合 → 空结果；Java 侧直接短路，不访问 Python、不租借凭据。
            return new KnowledgeSearchModels.Response(request.requestId(), null, request.strategy(), List.of(),
                    java.util.Map.of());
        }
        String body;
        try {
            body = json.writeValueAsString(request);
        } catch (IOException serialization) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        }
        BusinessException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Outcome outcome = attempt(request, body);
            if (outcome.response() != null) {
                return outcome.response();
            }
            last = outcome.failure();
            if (!outcome.retryable()) {
                break;
            }
        }
        throw last;
    }

    private Outcome attempt(KnowledgeSearchModels.Request request, String body) {
        String token;
        try {
            token = tokens.knowledgeSearchToken(request.userId(), request.projectId());
        } catch (RuntimeException unsupported) {
            log.error("knowledge search token unavailable (fail closed)");
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        }
        if (token == null || token.isBlank()) {
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + SEARCH_PATH))
                .timeout(Duration.ofMillis(request.deadlineMs()).plus(READ_GRACE))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        } catch (HttpTimeoutException timeout) {
            log.warn("knowledge search timed out: requestId={} elapsedMs={}", request.requestId(), elapsed(started));
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_TIMEOUT));
        } catch (IOException transport) {
            log.warn("knowledge search transport failure: requestId={} type={}", request.requestId(),
                    transport.getClass().getSimpleName());
            return Outcome.retry(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Outcome.ok(parse(request, response.body()));
        }
        log.warn("knowledge search rejected: requestId={} status={} elapsedMs={}", request.requestId(), status,
                elapsed(started));
        return classify(status);
    }

    static Outcome classify(int status) {
        if (status == 400) {
            return Outcome.fatal(new BusinessException(ErrorCode.VALIDATION_ERROR));
        }
        if (status == 401 || status == 403) {
            // 令牌或身份问题是本地缺陷，不暴露为用户可重试的错误，也不重试。
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        }
        if (status == 409) {
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE));
        }
        if (status == 504) {
            return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_TIMEOUT));
        }
        if (status >= 500) {
            return Outcome.retry(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
        }
        return Outcome.fatal(new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE));
    }

    private KnowledgeSearchModels.Response parse(KnowledgeSearchModels.Request request, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_RESPONSE_BYTES) {
            throw contractViolation(request, "body_size");
        }
        KnowledgeSearchModels.Response parsed;
        try {
            parsed = json.readValue(payload, KnowledgeSearchModels.Response.class);
        } catch (IOException invalid) {
            throw contractViolation(request, "body_unparseable");
        }
        if (parsed == null || parsed.hits() == null) {
            throw contractViolation(request, "hits_missing");
        }
        if (!request.requestId().equals(parsed.requestId())) {
            throw contractViolation(request, "request_id_mismatch");
        }
        if (parsed.hits().size() > MAX_RESPONSE_HITS) {
            throw contractViolation(request, "too_many_hits");
        }
        for (KnowledgeSearchModels.Hit hit : parsed.hits()) {
            if (hit == null) {
                throw contractViolation(request, "hit_null");
            }
            Double score = hit.scores() == null ? null : hit.scores().finalScore();
            if (score == null || score.isNaN() || score < 0.0 || score > 1.0) {
                throw contractViolation(request, "final_score_out_of_range");
            }
        }
        return parsed;
    }

    private static BusinessException contractViolation(KnowledgeSearchModels.Request request, String reason) {
        log.warn("knowledge search response violates contract: requestId={} reason={}", request.requestId(), reason);
        return new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
    }

    /** 契约 §4.4 约束；违规一律 400 VALIDATION_ERROR，不发请求。 */
    static void validate(KnowledgeSearchModels.Request request) {
        if (request == null || request.requestId() == null || request.projectId() == null
                || request.userId() == null || request.intent() == null || request.rerank() == null
                || request.strategy() == null || !VALID_STRATEGIES.contains(request.strategy())
                || request.originalQuery() == null || request.originalQuery().isBlank()
                || request.originalQuery().length() > MAX_QUERY_CHARS
                || request.visibleDocumentIds() == null || request.queries() == null
                || request.headingFilter() == null || request.expand() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (!validOptionalFilter(request.headingFilter().exact())
                || !validOptionalFilter(request.headingFilter().contains())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<UUID> ids = request.visibleDocumentIds();
        if (ids.size() > MAX_VISIBLE_DOCUMENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "可见文档数超过上限 " + MAX_VISIBLE_DOCUMENTS);
        }
        Set<UUID> unique = new HashSet<>(ids);
        if (unique.contains(null) || unique.size() != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<KnowledgeSearchModels.Query> queries = request.queries();
        if (queries.isEmpty() || queries.size() > MAX_QUERIES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        int primaries = 0;
        for (KnowledgeSearchModels.Query query : queries) {
            if (query == null || query.text() == null || query.text().isBlank()
                    || query.text().length() > MAX_QUERY_CHARS
                    || !("PRIMARY".equals(query.role()) || "SUB".equals(query.role()))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
            if ("PRIMARY".equals(query.role())) {
                primaries++;
            }
        }
        if (primaries != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (request.topK() < 1 || request.topK() > MAX_TOP_K
                || request.candidatePool() < request.topK() || request.candidatePool() > MAX_CANDIDATE_POOL
                || request.deadlineMs() < 1 || request.deadlineMs() > MAX_DEADLINE_MS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static boolean validOptionalFilter(String text) {
        return text == null || (!text.isBlank() && text.length() <= MAX_HEADING_FILTER_CHARS);
    }

    /** P2-R2 R2-2：candidatePool = max(20, 2*topK)。 */
    public static int candidatePool(int topK) {
        return Math.max(20, 2 * topK);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    record Outcome(KnowledgeSearchModels.Response response, BusinessException failure, boolean retryable) {
        static Outcome ok(KnowledgeSearchModels.Response response) {
            return new Outcome(response, null, false);
        }

        static Outcome retry(BusinessException failure) {
            return new Outcome(null, failure, true);
        }

        static Outcome fatal(BusinessException failure) {
            return new Outcome(null, failure, false);
        }
    }
}
