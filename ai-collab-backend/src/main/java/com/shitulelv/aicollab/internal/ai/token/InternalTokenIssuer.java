package com.shitulelv.aicollab.internal.ai.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 内部服务短令牌签发（冻结契约 §2.1）：手写 JWS Compact，不引入 JWT 库。
 *
 * <p>Header 固定 {@code {"alg":"EdDSA","typ":"JWT","kid":...}}；Payload 至少包含
 * {@code iss/sub/aud/scope/jti/iat/exp}，TTL 默认 300s。{@code extraClaims} 用于 W5 job token
 * （{@code jobId/attempt/leaseEpoch/...}），追加在保留 claim 之后，不允许覆盖保留 claim。</p>
 *
 * <p>Ed25519 签名是确定性的（RFC 8032），因此"同一私钥 + 同一 claims"必然产生<b>逐字节相同</b>
 * 的令牌——跨语言 fixture 正是以这一点为锚点。</p>
 *
 * <p><b>日志红线</b>：本类不打印令牌、私钥或 claims 值；异常消息只描述结构问题。</p>
 */
public final class InternalTokenIssuer {

    /** 签发者（契约固定值）。 */
    public static final String ISSUER = "ai-collab-backend";

    /** 主体（契约固定值）。 */
    public static final String SUBJECT = "backend";

    /** JWS 算法名。 */
    public static final String ALGORITHM = "EdDSA";

    /** JWS 类型。 */
    public static final String TYPE = "JWT";

    /** worker 侧要求的 audience。 */
    public static final String AUDIENCE_AI_WORKER = "ai-worker";

    /** Java 内部端点要求的 audience。 */
    public static final String AUDIENCE_BACKEND = "backend";

    /** rerank 能力 scope。 */
    public static final String SCOPE_RERANK = "rerank";

    /** 模型调用 scope。 */
    public static final String SCOPE_MODEL_CALL = "model-call";

    /** Agent 单轮决策 scope（W4 decide shadow）。 */
    public static final String SCOPE_DECIDE = "decide";

    /** 查询规划 scope（W4 query plan）。 */
    public static final String SCOPE_PLAN = "plan";

    /** 同步服务令牌的 audience 集合（worker 验 {@code ai-worker}，回传 Java 验 {@code backend}）。 */
    public static final List<String> SERVICE_AUDIENCES = List.of(AUDIENCE_AI_WORKER, AUDIENCE_BACKEND);

    /** 同步服务令牌的 scope 集合。 */
    public static final List<String> SERVICE_SCOPES =
            List.of(SCOPE_RERANK, SCOPE_MODEL_CALL, SCOPE_DECIDE, SCOPE_PLAN);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final List<String> RESERVED_CLAIMS = List.of(
            "iss", "sub", "aud", "scope", "jti", "iat", "exp");

    private final PrivateKey privateKey;
    private final String kid;
    private final long ttlSeconds;
    private final Clock clock;

    public InternalTokenIssuer(PrivateKey privateKey, String kid, long ttlSeconds) {
        this(privateKey, kid, ttlSeconds, Clock.systemUTC());
    }

    /** 测试可注入固定时钟，用于 59/61 秒偏移与跨语言 fixture。 */
    public InternalTokenIssuer(PrivateKey privateKey, String kid, long ttlSeconds, Clock clock) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("内部令牌 kid 不能为空");
        }
        if (ttlSeconds < InternalTokenProperties.MIN_TTL_SECONDS
                || ttlSeconds > InternalTokenProperties.MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("内部令牌 TTL 必须在 60..900 秒之间");
        }
        this.kid = kid;
        this.ttlSeconds = ttlSeconds;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String kid() {
        return kid;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 用当前时钟签发同步服务令牌（aud=ai-worker+backend，scope=rerank+model-call+decide+plan）。 */
    public String issueServiceToken() {
        return issue(SERVICE_AUDIENCES, SERVICE_SCOPES);
    }

    /** 用当前时钟签发令牌，TTL 取构造参数。 */
    public String issue(List<String> audiences, List<String> scopes) {
        return issue(audiences, scopes, Map.of());
    }

    /** 用当前时钟签发令牌，并附加 W5 job token 等扩展 claims。 */
    public String issue(List<String> audiences, List<String> scopes, Map<String, Object> extraClaims) {
        long issuedAt = clock.instant().getEpochSecond();
        return issueAt(audiences, scopes, issuedAt, issuedAt + ttlSeconds,
                UUID.randomUUID().toString(), extraClaims);
    }

    /**
     * 固定 {@code iat/exp/jti} 的签发入口：供跨语言 fixture 与时间边界测试使用。
     *
     * @throws IllegalArgumentException aud/scope 为空、jti 为空或 extraClaims 覆盖保留 claim
     */
    public String issueAt(List<String> audiences, List<String> scopes,
            long issuedAt, long expiresAt, String jti, Map<String, Object> extraClaims) {
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("内部令牌 aud 不能为空");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("内部令牌 scope 不能为空");
        }
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("内部令牌 jti 不能为空");
        }
        if (expiresAt <= issuedAt) {
            throw new IllegalArgumentException("内部令牌 exp 必须晚于 iat");
        }

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALGORITHM);
        header.put("typ", TYPE);
        header.put("kid", kid);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", ISSUER);
        payload.put("sub", SUBJECT);
        payload.put("aud", List.copyOf(audiences));
        payload.put("scope", String.join(" ", scopes));
        payload.put("jti", jti);
        payload.put("iat", issuedAt);
        payload.put("exp", expiresAt);
        if (extraClaims != null) {
            for (Map.Entry<String, Object> entry : extraClaims.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("extraClaims 的 claim 名不能为空");
                }
                if (RESERVED_CLAIMS.contains(name)) {
                    throw new IllegalArgumentException("extraClaims 不能覆盖保留 claim: " + name);
                }
                if (payload.containsKey(name)) {
                    throw new IllegalArgumentException("extraClaims 含重复 claim: " + name);
                }
                payload.put(name, entry.getValue());
            }
        }

        String headerPart = encode(serialize(header, "内部令牌 header"));
        String payloadPart = encode(serialize(payload, "内部令牌 payload"));
        String signingInput = headerPart + "." + payloadPart;
        String signature = encode(sign(signingInput));
        return signingInput + "." + signature;
    }

    private byte[] sign(String signingInput) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            // 不带上异常文本中的任何密钥材料；签名失败属于服务端配置/实现问题。
            throw new IllegalStateException("内部服务令牌签名失败", failure);
        }
    }

    private static byte[] serialize(Map<String, Object> value, String what) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(what + " 序列化失败", failure);
        }
    }

    private static String encode(byte[] value) {
        return URL_ENCODER.encodeToString(value);
    }
}

