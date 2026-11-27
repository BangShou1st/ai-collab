package com.shitulelv.aicollab.internal.ai.index;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Java → Python {@code /v1/index/generations/**} 管理客户端（P2 契约 §4.6、§12.6 R4-3/R4-4）。
 *
 * <ul>
 *   <li>每次 HTTP 尝试都现签 {@code index:admin} 短令牌；令牌不可用时 fail closed；</li>
 *   <li>5xx 与非超时传输错误最多重试 1 次（五类操作在契约里均幂等）；400/401/403/404/409 与超时不重试；</li>
 *   <li>严格禁止记录令牌、请求体 ID 明细或未校验的远端响应正文。</li>
 * </ul>
 */
@Component
public class IndexAdminClient {

    private static final Logger log = LoggerFactory.getLogger(IndexAdminClient.class);

    public static final int MAX_BATCH_DOCUMENTS = 5000;
    static final String GENERATIONS_PATH = "/v1/index/generations";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final long MAX_RESPONSE_BYTES = 4L * 1024L * 1024L;
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final AiIndexProperties properties;
    private final InternalTokenProvider tokens;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public IndexAdminClient(AiIndexProperties properties, InternalTokenProvider tokens, ObjectMapper objectMapper) {
        this(properties, tokens, objectMapper, OutboundHttpClients.builder()
                .connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build());
    }

    IndexAdminClient(AiIndexProperties properties, InternalTokenProvider tokens, ObjectMapper objectMapper,
            HttpClient http) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = Objects.requireNonNull(http, "http");
    }

    /** {@code GET /v1/index/generations}：列出所有 generation 状态、模型、维度、行数（Java 不落库）。 */
    public List<GenerationInfo> listGenerations() {
        byte[] body = execute("list_generations", "GET", GENERATIONS_PATH, null);
        JsonNode root = parseTree("list_generations", body);
        JsonNode array = root;
        if (root != null && root.isObject()) {
            if (root.has("generations")) {
                array = root.get("generations");
            } else if (root.has("items")) {
                array = root.get("items");
            }
        }
        if (array == null || !array.isArray()) {
            throw contractViolation("list_generations", "generations_not_array");
        }
        List<GenerationInfo> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            try {
                GenerationInfo info = json.treeToValue(item, GenerationInfo.class);
                if (info == null || info.id() == null || info.status() == null || info.status().isBlank()) {
                    throw contractViolation("list_generations", "generation_item_invalid");
                }
                result.add(info);
            } catch (IOException invalid) {
                throw contractViolation("list_generations", "generation_item_unparseable");
            }
        }
        return List.copyOf(result);
    }

    /**
     * 只读对账：{@code POST /v1/index/generations/{id}:reconcile} {@code {documentIds:[≤5000]}}。
     * 返回本批中该 generation 缺失 {@code indexed_document} 的文档 ID。
     */
    public List<UUID> reconcileMissing(UUID generationId, List<UUID> documentIds) {
        Objects.requireNonNull(generationId, "generationId");
        validateDocumentIds(documentIds);
        if (documentIds.isEmpty()) {
            return List.of();
        }
        String payload = writeJson(new ReconcileMissingRequest(documentIds));
        byte[] body = execute("reconcile_readonly", "POST", actionPath(generationId, "reconcile"), payload);
        JsonNode root = parseTree("reconcile_readonly", body);
        JsonNode idsNode = root;
        if (root != null && root.isObject()) {
            if (root.has("missingDocumentIds")) {
                idsNode = root.get("missingDocumentIds");
            } else if (root.has("documentIds")) {
                idsNode = root.get("documentIds");
            } else if (root.has("missing")) {
                idsNode = root.get("missing");
            }
        }
        if (idsNode == null || !idsNode.isArray()) {
            throw contractViolation("reconcile_readonly", "missing_ids_not_array");
        }
        List<UUID> missing = new ArrayList<>(idsNode.size());
        for (JsonNode idNode : idsNode) {
            if (idNode == null || !idNode.isTextual()) {
                throw contractViolation("reconcile_readonly", "missing_id_not_uuid");
            }
            try {
                missing.add(UUID.fromString(idNode.asText()));
            } catch (IllegalArgumentException invalid) {
                throw contractViolation("reconcile_readonly", "missing_id_invalid_uuid");
            }
        }
        return List.copyOf(missing);
    }

    /**
     * PRUNE 清理对账（R4-3）：{@code POST /v1/index/generations/{id}:reconcile}
     * {@code {mode:"PRUNE", asOf, range:{afterDocumentId, throughDocumentId}, documentIds:[≤5000]}}。
     * 即使 {@code documentIds} 为空也必须发请求，由 Python 删除区间内的全部孤儿文档。
     */
    public PruneResult reconcilePrune(UUID generationId, OffsetDateTime asOf, DocumentRange range,
            List<UUID> documentIds) {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(range, "range");
        validateDocumentIds(documentIds);
        String payload = writeJson(new PruneRequest(
                "PRUNE", asOf.toInstant().toString(), range, documentIds));
        byte[] body = execute("reconcile_prune", "POST", actionPath(generationId, "reconcile"), payload);
        JsonNode root = parseTree("reconcile_prune", body);
        if (root == null || !root.isObject() || !root.hasNonNull("deletedDocuments")
                || !root.hasNonNull("deletedRows")
                || !root.get("deletedDocuments").isIntegralNumber()
                || !root.get("deletedRows").isIntegralNumber()) {
            throw contractViolation("reconcile_prune", "prune_result_invalid");
        }
        long deletedDocuments = root.get("deletedDocuments").asLong();
        long deletedRows = root.get("deletedRows").asLong();
        if (deletedDocuments < 0 || deletedRows < 0) {
            throw contractViolation("reconcile_prune", "prune_result_negative");
        }
        return new PruneResult(deletedDocuments, deletedRows);
    }

    /** {@code POST /v1/index/generations/{id}:activate}（幂等）。 */
    public void activate(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        execute("activate", "POST", actionPath(generationId, "activate"), null);
    }

    /** {@code POST /v1/index/generations/{id}:abandon}（幂等）。 */
    public void abandon(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        execute("abandon", "POST", actionPath(generationId, "abandon"), null);
    }

    /** {@code POST /v1/index/generations/{id}:reopen}（R4-4，幂等；冲突时抛 409 CONFLICT）。 */
    public void reopen(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        execute("reopen", "POST", actionPath(generationId, "reopen"), null);
    }

    public void discard(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        execute("discard", "DELETE", "/v1/index/generations/" + generationId, null);
    }

    // ------------------------------------------------------------------
    // HTTP 执行与错误处理
    // ------------------------------------------------------------------

    private byte[] execute(String op, String method, String path, String jsonBody) {
        IndexAdminException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            AttemptOutcome outcome = attempt(op, method, path, jsonBody);
            if (outcome.succeeded()) {
                return outcome.body();
            }
            last = outcome.failure();
            if (!outcome.retryable()) {
                break;
            }
        }
        throw last;
    }

    private AttemptOutcome attempt(String op, String method, String path, String jsonBody) {
        String token;
        try {
            token = tokens.indexAdminToken();
        } catch (RuntimeException unavailable) {
            log.error("index admin token unavailable op={} (fail closed)", op);
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.UNAUTHORIZED, 0, null, "index admin token unavailable"));
        }
        if (token == null || token.isBlank()) {
            log.error("index admin token blank op={} (fail closed)", op);
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.UNAUTHORIZED, 0, null, "index admin token blank"));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.workerBaseUrl() + path))
                .timeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else if (jsonBody == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }

        long started = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.UNAVAILABLE, 0, null, "index admin call interrupted"));
        } catch (HttpTimeoutException timeout) {
            log.warn("index admin timed out op={} elapsedMs={}", op, elapsedMs(started));
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.UNAVAILABLE, 0, null, "index admin call timed out"));
        } catch (IOException transport) {
            log.warn("index admin transport failure op={} type={}", op, transport.getClass().getSimpleName());
            return AttemptOutcome.retry(new IndexAdminException(
                    IndexAdminException.Kind.UNAVAILABLE, 0, null, "index admin transport failure"));
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            byte[] payload = response.body();
            if (payload != null && payload.length > MAX_RESPONSE_BYTES) {
                throw contractViolation(op, "response_too_large");
            }
            return AttemptOutcome.ok(payload == null ? new byte[0] : payload);
        }

        String remoteCode = extractSafeErrorCode(response.body());
        log.warn("index admin rejected op={} status={} code={} elapsedMs={}",
                op, status, remoteCode != null ? remoteCode : "-", elapsedMs(started));
        return classifyError(status, remoteCode);
    }

    private static AttemptOutcome classifyError(int status, String remoteCode) {
        if (status == 400 || status == 422) {
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.BAD_REQUEST, status, remoteCode, "index admin request invalid"));
        }
        if (status == 401 || status == 403) {
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.UNAUTHORIZED, status, remoteCode, "index admin unauthorized"));
        }
        if (status == 404) {
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.NOT_FOUND, status, remoteCode, "index generation not found"));
        }
        if (status == 409) {
            return AttemptOutcome.fatal(new IndexAdminException(
                    IndexAdminException.Kind.CONFLICT, status, remoteCode, "index generation conflict"));
        }
        if (status >= 500) {
            return AttemptOutcome.retry(new IndexAdminException(
                    IndexAdminException.Kind.UNAVAILABLE, status, remoteCode, "index admin server unavailable"));
        }
        return AttemptOutcome.fatal(new IndexAdminException(
                IndexAdminException.Kind.UNAVAILABLE, status, remoteCode, "index admin unexpected status"));
    }

    private String extractSafeErrorCode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 64 * 1024) {
            return null;
        }
        try {
            JsonNode root = json.readTree(payload);
            if (root != null && root.has("code") && root.get("code").isTextual()) {
                String raw = root.get("code").asText();
                if (SAFE_CODE.matcher(raw).matches()) {
                    return raw;
                }
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体忽略原文，不透传任意文本。
        }
        return null;
    }

    private JsonNode parseTree(String op, byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw contractViolation(op, "body_empty");
        }
        try {
            return json.readTree(payload);
        } catch (IOException invalid) {
            throw contractViolation(op, "body_unparseable");
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalStateException("index admin request serialization failed", failure);
        }
    }

    private static void validateDocumentIds(List<UUID> documentIds) {
        if (documentIds == null) {
            throw new IllegalArgumentException("documentIds 不能为空");
        }
        if (documentIds.size() > MAX_BATCH_DOCUMENTS) {
            throw new IllegalArgumentException("documentIds 单批不能超过 " + MAX_BATCH_DOCUMENTS);
        }
        for (UUID id : documentIds) {
            if (id == null) {
                throw new IllegalArgumentException("documentIds 不能包含 null");
            }
        }
    }

    private static String actionPath(UUID generationId, String action) {
        return GENERATIONS_PATH + "/" + generationId + ":" + action;
    }

    private static IndexAdminException contractViolation(String op, String reason) {
        log.warn("index admin response violates contract op={} reason={}", op, reason);
        return new IndexAdminException(IndexAdminException.Kind.CONTRACT_VIOLATION, 200, null,
                "index admin contract violation: " + reason);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    // ------------------------------------------------------------------
    // 契约模型与异常
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationInfo(
            @JsonAlias("generationId") UUID id,
            String embeddingModel,
            int dimension,
            String status,
            @JsonAlias({"indexedDocuments", "documents"}) long documentCount,
            @JsonAlias({"rows", "chunkCount"}) long rowCount,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DocumentRange(UUID afterDocumentId, UUID throughDocumentId) {
    }

    public record PruneResult(long deletedDocuments, long deletedRows) {
    }

    private record ReconcileMissingRequest(List<UUID> documentIds) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PruneRequest(String mode, String asOf, DocumentRange range, List<UUID> documentIds) {
    }

    private record AttemptOutcome(boolean succeeded, byte[] body, IndexAdminException failure, boolean retryable) {
        static AttemptOutcome ok(byte[] body) {
            return new AttemptOutcome(true, body, null, false);
        }

        static AttemptOutcome retry(IndexAdminException failure) {
            return new AttemptOutcome(false, null, failure, true);
        }

        static AttemptOutcome fatal(IndexAdminException failure) {
            return new AttemptOutcome(false, null, failure, false);
        }
    }

    public static final class IndexAdminException extends RuntimeException {
        public enum Kind {
            BAD_REQUEST,
            UNAUTHORIZED,
            NOT_FOUND,
            CONFLICT,
            UNAVAILABLE,
            CONTRACT_VIOLATION
        }

        private final Kind kind;
        private final int httpStatus;
        private final String errorCode;

        public IndexAdminException(Kind kind, int httpStatus, String errorCode, String message) {
            super(message);
            this.kind = kind;
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        public Kind kind() {
            return kind;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
