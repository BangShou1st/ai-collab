package com.shitulelv.aicollab.internal.ai.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签发器契约（冻结 §2.1/§2.6）：固定密钥 + 固定 claims 必须产生<b>逐字节稳定</b>的 JWS，
 * 并与跨语言 fixture 完全一致（该字符串同时是 Python 侧验签的锚点）。
 */
class InternalTokenIssuerTest {

    /** 跨语言 fixture 的固定令牌（与 tests/fixtures/auth/ed25519-test-fixture.json 同值）。 */
    static final String FIXTURE_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCIsImtpZCI6ImsxIn0."
            + "eyJpc3MiOiJhaS1jb2xsYWItYmFja2VuZCIsInN1YiI6ImJhY2tlbmQiLCJhdWQiOlsiYWktd29ya2VyIiwiYmFja2VuZCJd"
            + "LCJzY29wZSI6InJlcmFuayBtb2RlbC1jYWxsIiwianRpIjoiM2YxYzliMmUtN2Q0YS00YzFlLTlmNmEtMmI4ZDVlMGExYzczIiw"
            + "iaWF0IjoxNzYyOTAwMDAwLCJleHAiOjE3NjI5MDAzMDB9."
            + "FD8Tq4w6GQVv0SytlnhiNsB6jorJJuE5YXPKWaCm3FWLLoa7-2Bi6jYlL8sMZRS9hE2iA6wYsgsV_1vui70PCA";

    /** fixture 的固定 jti。 */
    static final String FIXTURE_JTI = "3f1c9b2e-7d4a-4c1e-9f6a-2b8d5e0a1c73";
    /** fixture 的固定 iat/exp（契约示例同一组秒级时间）。 */
    static final long FIXTURE_IAT = 1_762_900_000L;
    static final long FIXTURE_EXP = 1_762_900_300L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fixtureTokenIsReproducedByteForByte() throws Exception {
        JsonNode fixture = loadFixture();
        PrivateKey key = Ed25519Keys.parsePrivateKey(fixture.path("privateKeyPkcs8").asText());

        InternalTokenIssuer issuer = new InternalTokenIssuer(key, fixture.path("kid").asText(), 300,
                Clock.fixed(Instant.ofEpochSecond(FIXTURE_IAT), ZoneOffset.UTC));
        String generated = issuer.issueAt(
                List.of("ai-worker", "backend"),
                List.of("rerank", "model-call"),
                fixture.path("claims").path("iat").asLong(),
                fixture.path("claims").path("exp").asLong(),
                fixture.path("claims").path("jti").asText(),
                Map.of());

        // 三方锚点：Java 生成 == fixture 文件 == 测试内写死的常量。
        assertThat(generated).isEqualTo(fixture.path("token").asText());
        assertThat(generated).isEqualTo(FIXTURE_TOKEN);

        // fixture 公钥必须等于私钥派生的公钥（Python 侧用同一 SPKI 验签）。
        assertThat(Ed25519Keys.encodeBase64(
                Ed25519Keys.derivePublicKey(key).getEncoded()))
                .isEqualTo(fixture.path("publicKeySpki").asText());
    }

    @Test
    void payloadAndHeaderAreStableAndOrdered() throws Exception {
        JsonNode fixture = loadFixture();
        String generated = FIXTURE_TOKEN;
        String[] parts = generated.split("\\.");
        assertThat(parts).hasSize(3);

        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        assertThat(header).isEqualTo("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}");

        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).isEqualTo(
                "{\"iss\":\"ai-collab-backend\",\"sub\":\"backend\",\"aud\":[\"ai-worker\",\"backend\"],"
                        + "\"scope\":\"rerank model-call\",\"jti\":\"" + FIXTURE_JTI + "\","
                        + "\"iat\":1762900000,\"exp\":1762900300}");
        assertThat(fixture.path("claims").path("scope").asText()).isEqualTo("rerank model-call");
    }

    @Test
    void issuesContractClaimsWithTtlAndUniqueJti() throws Exception {
        KeyPair keyPair = newKeyPair();
        long now = 1_800_000_000L;
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k7", 300,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));

        String first = issuer.issueServiceToken();
        String second = issuer.issueServiceToken();
        JsonNode firstPayload = payload(first);

        assertThat(firstPayload.path("iss").asText()).isEqualTo("ai-collab-backend");
        assertThat(firstPayload.path("sub").asText()).isEqualTo("backend");
        assertThat(firstPayload.path("scope").asText()).isEqualTo("rerank model-call");
        assertThat(firstPayload.path("aud")).hasSize(2);
        assertThat(firstPayload.path("aud").get(0).asText()).isEqualTo("ai-worker");
        assertThat(firstPayload.path("aud").get(1).asText()).isEqualTo("backend");
        assertThat(firstPayload.path("iat").asLong()).isEqualTo(now);
        assertThat(firstPayload.path("exp").asLong()).isEqualTo(now + 300);
        assertThat(firstPayload.path("jti").asText()).isNotBlank();
        assertThat(firstPayload.path("jti").asText()).isNotEqualTo(payload(second).path("jti").asText());

        String header = new String(Base64.getUrlDecoder().decode(first.split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"EdDSA\"").contains("\"typ\":\"JWT\"")
                .contains("\"kid\":\"k7\"");
    }

    @Test
    void extraClaimsAreAppendedForJobTokens() throws Exception {
        KeyPair keyPair = newKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("jobId", "job-42");
        extra.put("attempt", 2);
        extra.put("leaseEpoch", 7);

        JsonNode claims = payload(issuer.issue(List.of("ai-worker"), List.of("job:results"), extra));
        assertThat(claims.path("jobId").asText()).isEqualTo("job-42");
        assertThat(claims.path("attempt").asInt()).isEqualTo(2);
        assertThat(claims.path("leaseEpoch").asInt()).isEqualTo(7);
        assertThat(claims.path("scope").asText()).isEqualTo("job:results");

        assertThatThrownBy(() -> issuer.issue(List.of("ai-worker"), List.of("rerank"),
                Map.of("iss", "evil")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留 claim");
    }

    @Test
    void derivedPublicKeyMatchesRfc8032TestVector() {
        // RFC 8032 §7.1 TEST 1（seed -> public key）。
        byte[] seed = HexFormat.of().parseHex(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        assertThat(Ed25519Keys.hexPublicKeyFromSeed(seed))
                .isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    }

    @Test
    void invalidClaimsAreRejectedWithoutLeakingKeyMaterial() {
        KeyPair keyPair = newKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300);
        assertThatThrownBy(() -> issuer.issueAt(List.of(), List.of("rerank"), 1L, 2L, "j", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aud");
        assertThatThrownBy(() -> issuer.issueAt(List.of("ai-worker"), List.of(), 1L, 2L, "j", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> new InternalTokenIssuer(keyPair.getPrivate(), "k1", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
    }

    static JsonNode loadFixture() throws Exception {
        try (InputStream stream = InternalTokenIssuerTest.class
                .getResourceAsStream("/internal-ai/ed25519-test-fixture.json")) {
            assertThat(stream).as("跨语言 fixture 必须存在于测试资源").isNotNull();
            return MAPPER.readTree(stream);
        }
    }

    private static JsonNode payload(String token) throws Exception {
        String[] parts = token.split("\\.");
        return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
