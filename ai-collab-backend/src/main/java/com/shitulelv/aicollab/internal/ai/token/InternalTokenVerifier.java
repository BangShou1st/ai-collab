package com.shitulelv.aicollab.internal.ai.token;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内部服务短令牌验签（冻结契约 §2.3 的 9 条规则）。
 *
 * <p><b>fail closed 且不抛异常</b>：所有失败都返回带 {@link Reason} 的 {@link Result}，
 * 调用方（过滤器/端点）据此返回 401，不需要 try/catch。任何分支都不打印、不回显令牌；
 * {@link Result#claims()} 只含 payload，不含原始令牌。</p>
 *
 * <p>规则顺序与契约一致：三段/base64 → alg/typ → kid → 签名 → iss/sub → aud → scope →
 * 时间（±skew）。签名未通过前不解析、不信任 claims。</p>
 */
public final class InternalTokenVerifier {

    /** 令牌最大长度：超过即视为畸形，避免用超长输入消耗解析资源。 */
    static final int MAX_TOKEN_CHARS = 8192;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String EXPECTED_ALG = InternalTokenIssuer.ALGORITHM;
    private static final String EXPECTED_TYP = InternalTokenIssuer.TYPE;
    private static final String EXPECTED_ISSUER = InternalTokenIssuer.ISSUER;
    private static final String EXPECTED_SUBJECT = InternalTokenIssuer.SUBJECT;
    private static final Set<String> EMPTY_SCOPES = Set.of();

    private final Map<String, PublicKey> publicKeys;
    private final int clockSkewSeconds;
    private final Clock clock;

    public InternalTokenVerifier(Map<String, PublicKey> publicKeys, int clockSkewSeconds) {
        this(publicKeys, clockSkewSeconds, Clock.systemUTC());
    }

    /** 测试可注入固定时钟（±59/61 秒与 fixture 需要）。 */
    public InternalTokenVerifier(Map<String, PublicKey> publicKeys, int clockSkewSeconds, Clock clock) {
        this.publicKeys = publicKeys == null ? Map.of() : Map.copyOf(publicKeys);
        this.clockSkewSeconds = Math.max(0, clockSkewSeconds);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** static 模式占位：过滤器不会调用；保留 bean 形状，避免条件装配分支。 */
    public static InternalTokenVerifier disabled(int clockSkewSeconds) {
        return new InternalTokenVerifier(Map.of(), clockSkewSeconds);
    }

    /** 已知 kid 集合（只读；供启动自检/日志计数，不含密钥内容）。 */
    public Set<String> knownKids() {
        return publicKeys.keySet();
    }

    /**
     * 验签并校验 audience/scope。
     *
     * @param token             完整 JWS Compact 字符串
     * @param requiredAudience  本端要求的 audience（worker: {@code ai-worker}；Java: {@code backend}）
     * @param requiredScopes    本次操作要求的 scope 集合（空集表示只校验 audience）
     */
    public Result verify(String token, String requiredAudience, Set<String> requiredScopes) {
        try {
            return doVerify(token, requiredAudience, requiredScopes == null ? EMPTY_SCOPES : requiredScopes);
        } catch (RuntimeException unexpected) {
            // 防御：任何实现层异常都归为畸形令牌，绝不把异常抛给调用方。
            return Result.failure(Reason.MALFORMED);
        }
    }

    private Result doVerify(String token, String requiredAudience, Set<String> requiredScopes) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) {
            return Result.failure(Reason.MALFORMED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Result.failure(Reason.MALFORMED);
        }

        byte[] headerBytes = decode(parts[0]);
        if (headerBytes == null) {
            return Result.failure(Reason.MALFORMED);
        }
        JsonNode header = parse(headerBytes);
        if (header == null || !header.isObject()) {
            return Result.failure(Reason.MALFORMED);
        }
        if (!EXPECTED_ALG.equals(text(header, "alg"))) {
            return Result.failure(Reason.ALG);
        }
        JsonNode typNode = header.get("typ");
        if (typNode != null && !typNode.isNull() && !EXPECTED_TYP.equals(text(header, "typ"))) {
            return Result.failure(Reason.ALG);
        }
        String kid = text(header, "kid");
        if (kid == null || kid.isBlank()) {
            return Result.failure(Reason.UNKNOWN_KID);
        }
        PublicKey publicKey = publicKeys.get(kid);
        if (publicKey == null) {
            return Result.failure(Reason.UNKNOWN_KID);
        }

        byte[] signatureBytes = decode(parts[2]);
        if (signatureBytes == null || signatureBytes.length != 64) {
            return Result.failure(Reason.BAD_SIGNATURE);
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!signatureValid(publicKey, signingInput, signatureBytes)) {
            return Result.failure(Reason.BAD_SIGNATURE);
        }

        byte[] payloadBytes = decode(parts[1]);
        if (payloadBytes == null) {
            return Result.failure(Reason.MALFORMED);
        }
        JsonNode payload = parse(payloadBytes);
        if (payload == null || !payload.isObject()) {
            return Result.failure(Reason.MALFORMED);
        }

        if (!EXPECTED_ISSUER.equals(text(payload, "iss"))
                || !EXPECTED_SUBJECT.equals(text(payload, "sub"))) {
            return Result.failure(Reason.SUBJECT);
        }
        if (!audienceContains(payload.get("aud"), requiredAudience)) {
            return Result.failure(Reason.AUDIENCE);
        }
        if (!scopeContains(payload.get("scope"), requiredScopes)) {
            return Result.failure(Reason.SCOPE);
        }

        JsonNode expNode = payload.get("exp");
        JsonNode iatNode = payload.get("iat");
        if (expNode == null || !expNode.isIntegralNumber()
                || iatNode == null || !iatNode.isIntegralNumber()) {
            return Result.failure(Reason.MALFORMED);
        }
        long now = clock.instant().getEpochSecond();
        if (expNode.asLong() <= now - clockSkewSeconds) {
            return Result.failure(Reason.EXPIRED);
        }
        if (iatNode.asLong() > now + clockSkewSeconds) {
            return Result.failure(Reason.EXPIRED);
        }

        return Result.success(toClaims(payloadBytes));
    }

    private static boolean signatureValid(PublicKey publicKey, String signingInput, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return false;
        }
    }

    private static boolean audienceContains(JsonNode audNode, String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            return true;
        }
        if (audNode == null) {
            return false;
        }
        if (audNode.isTextual()) {
            return requiredAudience.equals(audNode.asText());
        }
        if (!audNode.isArray()) {
            return false;
        }
        for (JsonNode item : audNode) {
            if (item != null && item.isTextual() && requiredAudience.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean scopeContains(JsonNode scopeNode, Set<String> requiredScopes) {
        if (requiredScopes.isEmpty()) {
            return true;
        }
        if (scopeNode == null || !scopeNode.isTextual()) {
            return false;
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String item : scopeNode.asText().split("\\s+")) {
            if (!item.isBlank()) {
                granted.add(item);
            }
        }
        for (String required : requiredScopes) {
            if (!granted.contains(required)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> toClaims(byte[] payloadBytes) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(payloadBytes,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (Exception invalid) {
            return Map.of();
        }
    }

    private static byte[] decode(String segment) {
        if (segment == null || segment.isEmpty()) {
            return null;
        }
        try {
            return URL_DECODER.decode(segment);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static JsonNode parse(byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** 验签结果（不携带原始令牌）。 */
    public record Result(boolean valid, Reason reason, Map<String, Object> claims) {

        public static Result success(Map<String, Object> claims) {
            return new Result(true, null, claims);
        }

        /** 供过滤器等跨包调用方构造失败结果（不携带令牌）。 */
        public static Result failure(Reason reason) {
            return new Result(false, Objects.requireNonNull(reason, "reason"), Map.of());
        }

        /** 失败原因的外部可读名（日志字段；不含令牌）。 */
        public String reasonName() {
            return valid ? "ok" : reason.wireName();
        }
    }

    /** 失败原因（与 Python {@code AuthReason} 逐条一致；日志只用 {@link #wireName()}）。 */
    public enum Reason {

        MALFORMED("malformed"),
        ALG("alg"),
        UNKNOWN_KID("unknown_kid"),
        BAD_SIGNATURE("bad_signature"),
        SUBJECT("subject"),
        AUDIENCE("audience"),
        SCOPE("scope"),
        EXPIRED("expired");

        private final String wireName;

        Reason(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
