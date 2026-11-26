package com.shitulelv.aicollab.internal.ai.token;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部令牌装配：只支持 Ed25519。私钥缺失/非法在启动阶段失败，且失败信息（含异常链与栈）
 * 不含任何密钥内容。额外覆盖 TTL/时钟偏移的边界收敛与 Spring 配置绑定。
 */
class InternalTokenConfigurationTest {

    private final InternalTokenConfiguration configuration = new InternalTokenConfiguration();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InternalTokenConfiguration.class);

    @Test
    void missingPrivateKeyFailsAtStartup() {
        InternalTokenProperties properties = properties("", "k1", "");

        assertThatThrownBy(() -> configuration.internalTokenIssuer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal.ai.token.private-key")
                .hasNoCause();
        assertThatThrownBy(() -> configuration.internalTokenVerifier(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal.ai.token.private-key");
    }

    @Test
    void invalidPrivateKeyFailsWithoutLeakingTheValue() {
        String invalid = "TEST-ONLY-NOT-A-PKCS8-KEY";
        // 合法 base64、但不是 Ed25519 PKCS#8：解析器内部异常可能携带输入片段，必须被吞掉。
        String wrongShape = Base64.getEncoder().encodeToString("TEST-ONLY-wrong-shape-key-bytes".getBytes());
        for (String value : new String[] {invalid, wrongShape}) {
            InternalTokenProperties properties = properties(value, "k1", "");
            assertThatThrownBy(() -> configuration.internalTokenIssuer(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private-key")
                    .hasNoCause()
                    .satisfies(failure -> assertThat(fullTrace(failure)).doesNotContain(value));
        }
    }

    @Test
    void publicKeyMismatchFailureNeverEchoesKeyMaterial() {
        KeyMaterial material = newMaterial();
        KeyMaterial other = newMaterial();
        InternalTokenProperties properties = properties(material.privateBase64(), "k1",
                "k1=" + other.publicBase64());

        assertThatThrownBy(() -> configuration.internalTokenVerifier(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid=k1")
                .satisfies(failure -> assertThat(fullTrace(failure))
                        .doesNotContain(material.privateBase64())
                        .doesNotContain(other.publicBase64()));
    }

    @Test
    void propertiesToStringRedactsKeyMaterial() {
        KeyMaterial material = newMaterial();
        InternalTokenProperties properties = properties(material.privateBase64(), "k1",
                "k2=" + material.publicBase64());

        assertThat(properties.toString())
                .contains("kid=k1")
                .doesNotContain(material.privateBase64())
                .doesNotContain(material.publicBase64());
    }

    @Test
    void wiresIssuerAndDerivesCurrentKidPublicKey() {
        KeyMaterial material = newMaterial();
        InternalTokenProperties properties = new InternalTokenProperties(
                material.privateBase64(), "k9", 120, 60, "");

        InternalTokenProvider provider = configuration.internalTokenProvider(
                configuration.internalTokenIssuer(properties));
        InternalTokenVerifier verifier = configuration.internalTokenVerifier(properties);

        assertThat(provider).isInstanceOf(Ed25519InternalTokenProvider.class);
        assertThat(verifier.knownKids()).containsExactly("k9");

        String token = provider.token();
        InternalTokenVerifier.Result result = verifier.verify(token, "backend", Set.of("model-call"));
        assertThat(result.valid()).isTrue();
        assertThat(((Number) result.claims().get("exp")).longValue()
                - ((Number) result.claims().get("iat")).longValue()).isEqualTo(120);
    }

    @Test
    void duplicateOrMalformedPublicKeysFailAtStartup() {
        KeyMaterial material = newMaterial();
        InternalTokenProperties duplicate = properties(material.privateBase64(), "k1",
                "k2=" + material.publicBase64() + ",k2=" + material.publicBase64());
        assertThatThrownBy(() -> configuration.internalTokenVerifier(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复 kid=k2");

        InternalTokenProperties malformed = properties(material.privateBase64(), "k1", "not-a-pair");
        assertThatThrownBy(() -> configuration.internalTokenVerifier(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-keys");
    }

    @Test
    void ttlClockSkewAndKidAreNormalized() {
        InternalTokenProperties low = new InternalTokenProperties("", " ", 1, -5, null);
        assertThat(low.ttlSeconds()).isEqualTo(60);
        assertThat(low.clockSkewSeconds()).isZero();
        assertThat(low.kid()).isEqualTo(InternalTokenProperties.DEFAULT_KID);
        assertThat(low.publicKeys()).isEmpty();

        InternalTokenProperties high = new InternalTokenProperties("", "k1", 100_000, 100_000, "");
        assertThat(high.ttlSeconds()).isEqualTo(900);
        assertThat(high.clockSkewSeconds()).isEqualTo(300);
    }

    @Test
    void springBindsDefaultsAndIssuesTokens() {
        KeyMaterial material = newMaterial();
        contextRunner
                .withPropertyValues("internal.ai.token.private-key=" + material.privateBase64())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InternalTokenProperties bound = context.getBean(InternalTokenProperties.class);
                    assertThat(bound.kid()).isEqualTo("k1");
                    assertThat(bound.ttlSeconds()).isEqualTo(300);
                    assertThat(bound.clockSkewSeconds()).isEqualTo(60);
                    assertThat(context.getBean(InternalTokenProvider.class))
                            .isInstanceOf(Ed25519InternalTokenProvider.class);
                    assertThat(context.getBean(InternalTokenVerifier.class).knownKids())
                            .containsExactly("k1");
                    assertThat(context.getBean(InternalTokenProvider.class).token()).startsWith("eyJ");
                });
    }

    @Test
    void springStartupFailsWithoutPrivateKey() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(fullTrace(context.getStartupFailure()))
                    .contains("internal.ai.token.private-key");
        });
    }

    @Test
    void springStartupFailureForInvalidKeyNeverEchoesMaterial() {
        String invalid = Base64.getEncoder().encodeToString("TEST-ONLY-garbage-private-key".getBytes());
        contextRunner
                .withPropertyValues("internal.ai.token.private-key=" + invalid)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String trace = fullTrace(context.getStartupFailure());
                    assertThat(trace).contains("internal.ai.token.private-key");
                    assertThat(trace).doesNotContain(invalid);
                });
    }

    private static InternalTokenProperties properties(String privateKey, String kid, String publicKeys) {
        return new InternalTokenProperties(privateKey, kid, 300, 60, publicKeys);
    }

    /** 完整异常链 + 栈文本：覆盖 message、cause 与 suppressed。 */
    private static String fullTrace(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static KeyMaterial newMaterial() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new KeyMaterial(pair);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record KeyMaterial(KeyPair pair) {

        String privateBase64() {
            return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        }

        String publicBase64() {
            return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        }
    }
}
