package com.shitulelv.aicollab.internal.ai.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验签契约（冻结 §2.3）：必测矩阵 #1–#11 在 Java 侧逐条覆盖。
 *
 * <p>固定时钟 {@link #NOW} 让 ±59/±61 秒偏移与 exp/iat 边界可重复；所有失败只返回
 * {@link InternalTokenVerifier.Reason}，不抛异常、不回显令牌。</p>
 */
class InternalTokenVerifierTest {

    private static final long NOW = 1_762_900_100L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String KID = "k1";
    private static final Set<String> RERANK = Set.of("rerank");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrivateKey signingKey;
    private PublicKey publicKey;
    private InternalTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        KeyPair keyPair = newKeyPair();
        signingKey = keyPair.getPrivate();
        publicKey = keyPair.getPublic();
        issuer = new InternalTokenIssuer(signingKey, KID, 300, CLOCK);
    }

    // ---------- #1 合法令牌 ----------

    @Test
    void validTokenPassesAndExposesClaims() {
        String token = serviceToken(NOW, NOW + 300, "jti-1", List.of("ai-worker", "backend"),
                List.of("rerank", "model-call"));

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", RERANK);

        assertThat(result.valid()).isTrue();
        assertThat(result.reasonName()).isEqualTo("ok");
        assertThat(result.claims()).containsEntry("iss", "ai-collab-backend")
                .containsEntry("sub", "backend")
                .containsEntry("scope", "rerank model-call")
                .containsEntry("jti", "jti-1");
        assertThat(((Number) result.claims().get("iat")).longValue()).isEqualTo(NOW);
        assertThat(((Number) result.claims().get("exp")).longValue()).isEqualTo(NOW + 300);
        assertThat(result.claims().get("aud")).isEqualTo(List.of("ai-worker", "backend"));
        assertThat(result.toString()).doesNotContain(token);
    }

    // ---------- #2 过期 ----------

    @Test
    void expiredTokenIsRejected() {
        String token = serviceToken(NOW - 400, NOW - 120, "jti-2", List.of("ai-worker"), List.of("rerank"));

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", RERANK);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.EXPIRED);
        assertThat(result.claims()).isEmpty();
    }

    // ---------- #3 签名篡改 ----------

    @Test
    void tamperedSignatureIsRejected() {
        String token = serviceToken(NOW, NOW + 300, "jti-3", List.of("ai-worker"), List.of("rerank"));
        int signatureStart = token.lastIndexOf('.') + 1;
        // 改签名段首字符（末字符的 base64 低 4 位是填充位，可能解码回同一字节）。
        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement
                + token.substring(signatureStart + 1);

        InternalTokenVerifier.Result result = verifier().verify(tampered, "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.BAD_SIGNATURE);
    }

    // ---------- #4 audience ----------

    @Test
    void wrongAudienceIsRejected() {
        String token = serviceToken(NOW, NOW + 300, "jti-4", List.of("ai-worker"), List.of("rerank"));

        InternalTokenVerifier.Result result = verifier().verify(token, "backend", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.AUDIENCE);
    }

    // ---------- #5 subject ----------

    @Test
    void wrongSubjectIsRejected() throws Exception {
        String payload = "{\"iss\":\"ai-collab-backend\",\"sub\":\"worker\",\"aud\":[\"ai-worker\"],"
                + "\"scope\":\"rerank\",\"jti\":\"jti-5\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 300) + "}";
        String token = signRaw("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}", payload);

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.SUBJECT);
    }

    @Test
    void wrongIssuerIsRejectedAsSubject() throws Exception {
        String payload = "{\"iss\":\"other-backend\",\"sub\":\"backend\",\"aud\":[\"ai-worker\"],"
                + "\"scope\":\"rerank\",\"jti\":\"jti-5b\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 300) + "}";
        String token = signRaw("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}", payload);

        assertThat(verifier().verify(token, "ai-worker", RERANK).reason())
                .isEqualTo(InternalTokenVerifier.Reason.SUBJECT);
    }

    // ---------- #6 未知 kid ----------

    @Test
    void unknownKidIsRejected() {
        KeyPair other = newKeyPair();
        InternalTokenIssuer otherIssuer = new InternalTokenIssuer(other.getPrivate(), "k2", 300, CLOCK);
        String token = otherIssuer.issueAt(List.of("ai-worker"), List.of("rerank"), NOW, NOW + 300,
                "jti-6", Map.of());

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.UNKNOWN_KID);
    }

    // ---------- #7/#8 时钟偏移 ----------

    @Test
    void minusFiftyNineSecondsSkewStillPasses() {
        String token = serviceToken(NOW - 400, NOW - 59, "jti-7a", List.of("ai-worker"), List.of("rerank"));

        assertThat(verifier().verify(token, "ai-worker", RERANK).valid()).isTrue();
    }

    @Test
    void minusSixtyOneSecondsSkewIsExpired() {
        String token = serviceToken(NOW - 400, NOW - 61, "jti-7b", List.of("ai-worker"), List.of("rerank"));

        assertThat(verifier().verify(token, "ai-worker", RERANK).reason())
                .isEqualTo(InternalTokenVerifier.Reason.EXPIRED);
    }

    @Test
    void plusFiftyNineSecondsFutureIatPasses() {
        String token = serviceToken(NOW + 59, NOW + 300, "jti-8a", List.of("ai-worker"), List.of("rerank"));

        assertThat(verifier().verify(token, "ai-worker", RERANK).valid()).isTrue();
    }

    @Test
    void plusSixtyOneSecondsFutureIatIsRejected() {
        String token = serviceToken(NOW + 61, NOW + 300, "jti-8b", List.of("ai-worker"), List.of("rerank"));

        assertThat(verifier().verify(token, "ai-worker", RERANK).reason())
                .isEqualTo(InternalTokenVerifier.Reason.EXPIRED);
    }

    // ---------- #9/#10 轮换 ----------

    @Test
    void oldKidStillValidWhilePublicKeyPresent() {
        KeyPair oldPair = newKeyPair();
        KeyPair newPair = newKeyPair();
        InternalTokenIssuer oldIssuer = new InternalTokenIssuer(oldPair.getPrivate(), "k1", 300, CLOCK);
        String oldToken = oldIssuer.issueAt(List.of("ai-worker"), List.of("rerank"), NOW, NOW + 300,
                "jti-9", Map.of());
        InternalTokenVerifier rotated = new InternalTokenVerifier(
                Map.of("k1", oldPair.getPublic(), "k2", newPair.getPublic()), 60, CLOCK);

        assertThat(rotated.verify(oldToken, "ai-worker", RERANK).valid()).isTrue();
    }

    @Test
    void oldKidRejectedAfterKeyRemoved() {
        KeyPair oldPair = newKeyPair();
        KeyPair newPair = newKeyPair();
        InternalTokenIssuer oldIssuer = new InternalTokenIssuer(oldPair.getPrivate(), "k1", 300, CLOCK);
        String oldToken = oldIssuer.issueAt(List.of("ai-worker"), List.of("rerank"), NOW, NOW + 300,
                "jti-10", Map.of());
        InternalTokenVerifier rotated = new InternalTokenVerifier(
                Map.of("k2", newPair.getPublic()), 60, CLOCK);

        assertThat(rotated.verify(oldToken, "ai-worker", RERANK).reason())
                .isEqualTo(InternalTokenVerifier.Reason.UNKNOWN_KID);
    }

    // ---------- #11 alg=none / 篡改 header ----------

    @Test
    void algNoneIsRejected() {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"ai-collab-backend\",\"sub\":\"backend\",\"aud\":[\"ai-worker\"],"
                        + "\"scope\":\"rerank\",\"jti\":\"jti-11\",\"iat\":" + NOW + ",\"exp\":"
                        + (NOW + 300) + "}").getBytes(StandardCharsets.UTF_8));
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8));

        InternalTokenVerifier.Result result = verifier().verify(header + "." + payload + ".",
                "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.ALG);
    }

    @Test
    void tamperedHeaderIsRejected() throws Exception {
        String token = serviceToken(NOW, NOW + 300, "jti-12", List.of("ai-worker"), List.of("rerank"));
        String[] parts = token.split("\\.");
        ObjectNode header = (ObjectNode) MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        header.put("x-extra", "1");
        String tampered = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsBytes(header)) + "." + parts[1] + "." + parts[2];

        InternalTokenVerifier.Result result = verifier().verify(tampered, "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.BAD_SIGNATURE);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = serviceToken(NOW, NOW + 300, "jti-13", List.of("ai-worker"), List.of("rerank"));
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("\"scope\":\"rerank\"", "\"scope\":\"rerank \"").getBytes(StandardCharsets.UTF_8));

        InternalTokenVerifier.Result result = verifier()
                .verify(parts[0] + "." + tamperedPayload + "." + parts[2], "ai-worker", RERANK);

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.BAD_SIGNATURE);
    }

    @Test
    void missingTypIsToleratedButWrongTypIsRejected() throws Exception {
        String payload = "{\"iss\":\"ai-collab-backend\",\"sub\":\"backend\",\"aud\":[\"ai-worker\"],"
                + "\"scope\":\"rerank\",\"jti\":\"jti-14\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 300) + "}";
        String withoutTyp = signRaw("{\"alg\":\"EdDSA\",\"kid\":\"k1\"}", payload);
        String wrongTyp = signRaw("{\"alg\":\"EdDSA\",\"typ\":\"JWT2\",\"kid\":\"k1\"}", payload);

        assertThat(verifier().verify(withoutTyp, "ai-worker", RERANK).valid()).isTrue();
        assertThat(verifier().verify(wrongTyp, "ai-worker", RERANK).reason())
                .isEqualTo(InternalTokenVerifier.Reason.ALG);
    }

    // ---------- #7 scope ----------

    @Test
    void missingScopeIsRejected() {
        String token = serviceToken(NOW, NOW + 300, "jti-15", List.of("ai-worker"), List.of("rerank"));

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", Set.of("model-call"));

        assertThat(result.reason()).isEqualTo(InternalTokenVerifier.Reason.SCOPE);
    }

    @Test
    void allScopesAreRequired() {
        String token = serviceToken(NOW, NOW + 300, "jti-16", List.of("ai-worker"), List.of("rerank"));

        assertThat(verifier().verify(token, "ai-worker", Set.of("rerank", "model-call")).reason())
                .isEqualTo(InternalTokenVerifier.Reason.SCOPE);
    }

    // ---------- #1 结构畸形 / 卫生 ----------

    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        InternalTokenVerifier verifier = verifier();
        for (String token : new String[] {null, "", "   ", "abc", "a.b", "!!!.???.###",
                "eyJhbGciOiJFZERTQSJ9", "a.b.c"}) {
            InternalTokenVerifier.Result result = verifier.verify(token, "ai-worker", RERANK);
            assertThat(result.valid()).as("token=%s", token).isFalse();
            assertThat(result.reason()).isIn(
                    InternalTokenVerifier.Reason.MALFORMED,
                    InternalTokenVerifier.Reason.ALG,
                    InternalTokenVerifier.Reason.UNKNOWN_KID,
                    InternalTokenVerifier.Reason.BAD_SIGNATURE);
        }
    }

    @Test
    void failureOutputNeverContainsTokenOrClaims() {
        String token = serviceToken(NOW - 400, NOW - 120, "jti-17", List.of("ai-worker"), List.of("rerank"));

        InternalTokenVerifier.Result result = verifier().verify(token, "ai-worker", RERANK);

        assertThat(result.valid()).isFalse();
        assertThat(result.toString()).doesNotContain(token).doesNotContain("eyJ");
        assertThat(result.claims()).isEmpty();
        assertThatThrownBy(() -> result.claims().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reasonVocabularyMatchesCrossLanguageContract() {
        assertThat(java.util.Arrays.stream(InternalTokenVerifier.Reason.values())
                .map(InternalTokenVerifier.Reason::wireName).toList())
                .containsExactlyInAnyOrder("expired", "bad_signature", "unknown_kid", "audience",
                        "subject", "scope", "malformed", "alg");
    }

    // ---------- helpers ----------

    private InternalTokenVerifier verifier() {
        return new InternalTokenVerifier(Map.of(KID, publicKey), 60, CLOCK);
    }

    private String serviceToken(long iat, long exp, String jti, List<String> aud, List<String> scopes) {
        return issuer.issueAt(aud, scopes, iat, exp, jti, Map.of());
    }

    private String signRaw(String headerJson, String payloadJson) throws Exception {
        String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(signingKey);
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + base64Url(signer.sign());
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
