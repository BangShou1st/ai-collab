package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 llama.cpp 原生 {@code POST /rerank} 协议适配器。
 *
 * <p>协议：请求 {@code {query, documents:[title\ntext], top_n}}；响应
 * {@code {results:[{index, relevance_score}]}}。一次调用只发一次 HTTP，绝不重试；
 * 每个输入候选必须恰好得到一个有限分数，否则整次响应判非法并交给调用方回退。</p>
 *
 * <p>日志红线：只记录 providerId、outcome、reason、HTTP 状态与耗时，不记录 query、
 * 候选正文、密钥或模型路径。</p>
 */
@Component
public class LlamaCppRerankAdapter {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppRerankAdapter.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public LlamaCppRerankAdapter(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** 契约测试可注入固定 HttpClient。 */
    LlamaCppRerankAdapter(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
    }

    public RerankCallResult rerank(CapabilityProvider provider, RerankSettings settings, String query,
            List<RerankDocument> documents, Integer requestedTopK, String plaintextApiKey) {
        long startNanos = System.nanoTime();
        int candidateCount = documents == null ? 0 : documents.size();
        int topK = requestedTopK == null || requestedTopK < 1
                ? (settings == null ? Math.min(candidateCount, 8) : settings.topK())
                : requestedTopK;
        if (settings == null || provider == null) {
            return failure("INVALID_REQUEST", "configuration_missing", null, startNanos, candidateCount, topK);
        }
        topK = Math.max(1, Math.min(topK, Math.min(candidateCount, settings.maxCandidates())));
        if (query == null || query.isBlank()) {
            return failure("INVALID_REQUEST", "query_blank", null, startNanos, candidateCount, topK);
        }
        if (candidateCount < 1 || candidateCount > settings.maxCandidates()) {
            return failure("INVALID_REQUEST", "candidate_count_out_of_range", null, startNanos,
                    candidateCount, topK);
        }
        if (provider.requiresAuth() && (plaintextApiKey == null || plaintextApiKey.isBlank())) {
            return failure("INVALID_REQUEST", "credential_unavailable", null, startNanos,
                    candidateCount, topK);
        }
        for (RerankDocument document : documents) {
            if (document == null || document.text() == null || document.text().isBlank()) {
                return failure("INVALID_REQUEST", "document_text_blank", null, startNanos,
                        candidateCount, topK);
            }
        }

        HttpRequest request;
        try {
            request = buildRequest(provider, settings, query, documents, plaintextApiKey);
        } catch (RuntimeException invalid) {
            log.warn("rerank adapter 请求构建失败: providerId={} reason={}", provider.id(), invalid.getClass().getSimpleName());
            return failure("INVALID_REQUEST", "endpoint_invalid", null, startNanos, candidateCount, topK);
        }

        long sendStartNanos = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("TRANSPORT_ERROR", "interrupted", elapsedMillis(sendStartNanos),
                    startNanos, candidateCount, topK);
        } catch (HttpTimeoutException timeout) {
            log.warn("rerank adapter 超时: providerId={} timeoutMs={} candidates={}",
                    provider.id(), settings.timeoutMs(), candidateCount);
            return failure("TIMEOUT", "timeout", elapsedMillis(sendStartNanos), startNanos,
                    candidateCount, topK);
        } catch (IOException transport) {
            log.warn("rerank adapter 传输失败: providerId={} type={} candidates={}",
                    provider.id(), transport.getClass().getSimpleName(), candidateCount);
            return failure("TRANSPORT_ERROR", "transport_error", elapsedMillis(sendStartNanos),
                    startNanos, candidateCount, topK);
        }
        int modelLatencyMs = (int) Math.min(Integer.MAX_VALUE, elapsedMillis(sendStartNanos));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String reason = status >= 500 ? "http_5xx" : "http_4xx";
            log.warn("rerank adapter HTTP 失败: providerId={} status={} candidates={}",
                    provider.id(), status, candidateCount);
            return failure("HTTP_ERROR", reason, (long) modelLatencyMs, startNanos,
                    candidateCount, topK);
        }

        try {
            List<RerankScore> scores = parseScores(response.body(), documents, settings.scoreType());
            return RerankCallResult.success(scores, modelLatencyMs, elapsedMillis(startNanos),
                    candidateCount, topK);
        } catch (InvalidRerankResponse invalid) {
            log.warn("rerank adapter 响应非法: providerId={} reason={} status={} candidates={}",
                    provider.id(), invalid.getMessage(), status, candidateCount);
            return failure("INVALID_RESPONSE", invalid.getMessage(), (long) modelLatencyMs,
                    startNanos, candidateCount, topK);
        }
    }

    private HttpRequest buildRequest(CapabilityProvider provider, RerankSettings settings, String query,
            List<RerankDocument> documents, String plaintextApiKey) {
        List<String> payloadDocuments = new ArrayList<>(documents.size());
        for (RerankDocument document : documents) {
            String title = document.title() == null ? "" : document.title().strip();
            String text = document.text().strip();
            String combined = title.isEmpty() ? text : title + "\n" + text;
            payloadDocuments.add(truncateCodePoints(combined, settings.maxLength()));
        }
        // top_n 必须请求全量候选；topK 只用于客户端排序/裁剪。若传 topK，llama.cpp
        // 会对 9/10 个输入只返回 8 个 results，导致 adapter 的完整性校验正确拒绝。
        RequestPayload payload = new RequestPayload(query, payloadDocuments, payloadDocuments.size());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (IOException serialization) {
            throw new IllegalStateException("rerank 请求序列化失败", serialization);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpoint(provider))
                .timeout(Duration.ofMillis(settings.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (provider.requiresAuth() && plaintextApiKey != null && !plaintextApiKey.isBlank()) {
            switch (provider.authType().toUpperCase(java.util.Locale.ROOT)) {
                case "API_KEY" -> builder.header("X-API-Key", plaintextApiKey);
                case "BEARER" -> builder.header("Authorization", "Bearer " + plaintextApiKey);
                case "CUSTOM_HEADER" -> throw new IllegalArgumentException("CUSTOM_HEADER 需要显式 header 名，当前协议不支持");
                default -> {
                    // NONE：不发鉴权头。
                }
            }
        }
        return builder.build();
    }

    private static URI endpoint(CapabilityProvider provider) {
        String base = provider.baseUrl() == null ? "" : provider.baseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = provider.apiPath() == null ? "" : provider.apiPath().strip();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("apiPath 不能为空");
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI uri = URI.create(base + path);
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint 不是绝对地址");
        }
        return uri;
    }

    private List<RerankScore> parseScores(byte[] body, List<RerankDocument> documents, String scoreType) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (IOException invalidJson) {
            throw invalid("invalid_json");
        }
        if (root == null || !root.isObject()) {
            throw invalid("response_not_object");
        }
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            throw invalid("results_missing");
        }
        int candidateCount = documents.size();
        if (results.size() != candidateCount) {
            throw invalid("results_count_mismatch");
        }
        double[] indexed = new double[candidateCount];
        boolean[] seen = new boolean[candidateCount];
        for (JsonNode item : results) {
            if (item == null || !item.isObject()) {
                throw invalid("result_not_object");
            }
            JsonNode indexNode = item.get("index");
            JsonNode scoreNode = item.get("relevance_score");
            if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw invalid("index_invalid");
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= candidateCount) {
                throw invalid("index_out_of_range");
            }
            if (seen[index]) {
                throw invalid("index_duplicate");
            }
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw invalid("score_missing");
            }
            double score = scoreNode.asDouble();
            if (!Double.isFinite(score)) {
                throw invalid("score_not_finite");
            }
            seen[index] = true;
            indexed[index] = mapScore(score, scoreType);
        }
        for (boolean present : seen) {
            if (!present) {
                throw invalid("index_missing");
            }
        }
        List<RerankScore> scores = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) {
            scores.add(new RerankScore(index, documents.get(index).id(), indexed[index]));
        }
        return scores;
    }

    /** sigmoid/normalized 直接使用；logit 做数值稳定的 sigmoid，不在候选间重排分数分布。 */
    private static double mapScore(double score, String scoreType) {
        if (scoreType == null || !"logit".equalsIgnoreCase(scoreType)) {
            return score;
        }
        if (score >= 0) {
            double expNeg = Math.exp(-score);
            return 1.0 / (1.0 + expNeg);
        }
        double expPos = Math.exp(score);
        return expPos / (1.0 + expPos);
    }

    private static String truncateCodePoints(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private static RerankCallResult failure(String outcome, String reason, Long modelLatencyMs,
            long startNanos, int candidateCount, int topK) {
        return RerankCallResult.failure(outcome, reason,
                modelLatencyMs == null ? null : modelLatencyMs.intValue(), elapsedMillis(startNanos),
                candidateCount, topK);
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static InvalidRerankResponse invalid(String reason) {
        return new InvalidRerankResponse(reason);
    }

    private record RequestPayload(String query, List<String> documents,
                                  @JsonProperty("top_n") int topN) {
    }

    private static final class InvalidRerankResponse extends RuntimeException {
        private InvalidRerankResponse(String reason) {
            super(reason);
        }
    }
}
