package com.shitulelv.aicollab.internal.ai.token;

import com.shitulelv.aicollab.retrieval.rerank.RerankConfiguration;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模式装配（冻结 §2.2）：static 默认不变；ed25519/dual 私钥缺失/非法在启动阶段失败，且失败
 * 消息不含密钥内容。额外覆盖 TTL/时钟偏移的边界收敛与 Spring 配置绑定。
 */
class InternalTokenConfigurationTest {

    private final InternalTokenConfiguration configuration = new InternalTokenConfiguration();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InternalTokenConfiguration.class, RerankConfiguration.class);

    @Test
    void staticModeUsesStaticTokenAndDisabledVerifier() {
        InternalTokenProperties properties = InternalTokenProperties.defaults();
        RerankProperties rerank = rerank("static-token-w3s3");

        InternalTokenProvider provider = configuration.internalTokenProvider(properties, rerank);
        InternalTokenVerifier verifier = configuration.internalTokenVerifier(properties);

        assertThat(provider).isInstanceOf(StaticInternalTokenProvider.class);
        assertThat(provider.token()).isEqualTo("static-token-w3s3");
        assertThat(verifier.knownKids()).isEmpty();
    }

    @Test
    void ed25519ModeWithoutPrivateKeyFailsAtStartup() {
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.ED25519, "", "k1", 300, 60, "");

        assertThatThrownBy(() -> configuration.internalTokenProvider(properties, rerank("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key")
                .hasMessageNotContaining("MC4CAQ");
    }

    @Test
    void invalidPrivateKeyFailsWithoutLeakingTheValue() {
        String invalid = "TEST-ONLY-NOT-A-PKCS8-KEY";
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.ED25519, invalid, "k1", 300, 60, "");

        assertThatThrownBy(() -> configuration.internalTokenProvider(properties, rerank("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key")
                .hasMessageNotContaining(invalid);
    }

    @Test
    void ed25519ModeWiresIssuerAndDerivesCurrentKidPublicKey() throws Exception {
        KeyMaterial material = newMaterial();
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.ED25519, material.privateBase64(), "k9", 120, 60, "");

        InternalTokenProvider provider = configuration.internalTokenProvider(properties, rerank(""));
        InternalTokenVerifier verifier = configuration.internalTokenVerifier(properties);

        assertThat(provider).isInstanceOf(Ed25519InternalTokenProvider.class);
        assertThat(verifier.knownKids()).containsExactly("k9");

        String token = provider.token();
        InternalTokenVerifier.Result result = verifier.verify(token, "backend", java.util.Set.of("model-call"));
        assertThat(result.valid()).isTrue();
        assertThat(((Number) result.claims().get("exp")).longValue()
                - ((Number) result.claims().get("iat")).longValue()).isEqualTo(120);
    }

    @Test
    void mismatchedConfiguredCurrentKidKeyFailsAtStartup() {
        KeyMaterial material = newMaterial();
        KeyMaterial other = newMaterial();
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.ED25519, material.privateBase64(), "k1", 300, 60,
                "k1=" + other.publicBase64());

        assertThatThrownBy(() -> configuration.internalTokenVerifier(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid=k1");
    }

    @Test
    void duplicateOrMalformedPublicKeysFailAtStartup() {
        KeyMaterial material = newMaterial();
        InternalTokenProperties duplicate = new InternalTokenProperties(
                InternalTokenMode.ED25519, material.privateBase64(), "k1", 300, 60,
                "k2=" + material.publicBase64() + ",k2=" + material.publicBase64());
        assertThatThrownBy(() -> configuration.internalTokenVerifier(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复 kid=k2");

        InternalTokenProperties malformed = new InternalTokenProperties(
                InternalTokenMode.ED25519, material.privateBase64(), "k1", 300, 60, "not-a-pair");
        assertThatThrownBy(() -> configuration.internalTokenVerifier(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-keys");
    }

    @Test
    void dualModeIssuesJwtAndAcceptsStaticToo() {
        KeyMaterial material = newMaterial();
        InternalTokenProperties properties = new InternalTokenProperties(
                InternalTokenMode.DUAL, material.privateBase64(), "k1", 300, 60, "");
        RerankProperties rerank = rerank("dual-static");

        InternalTokenProvider provider = configuration.internalTokenProvider(properties, rerank);
        InternalTokenVerifier verifier = configuration.internalTokenVerifier(properties);

        assertThat(provider).isInstanceOf(Ed25519InternalTokenProvider.class);
        assertThat(provider.token()).startsWith("eyJ");
        assertThat(verifier.knownKids()).containsExactly("k1");
    }

    @Test
    void ttlAndClockSkewAreClamped() {
        InternalTokenProperties low = new InternalTokenProperties(
                InternalTokenMode.STATIC, "", "k1", 1, -5, "");
        assertThat(low.ttlSeconds()).isEqualTo(60);
        assertThat(low.clockSkewSeconds()).isZero();

        InternalTokenProperties high = new InternalTokenProperties(
                InternalTokenMode.STATIC, "", "k1", 100_000, 100_000, "");
        assertThat(high.ttlSeconds()).isEqualTo(900);
        assertThat(high.clockSkewSeconds()).isEqualTo(300);
    }

    @Test
    void springBindsStaticModeByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InternalTokenProperties.class).mode())
                    .isEqualTo(InternalTokenMode.STATIC);
            assertThat(context.getBean(InternalTokenProperties.class).ttlSeconds()).isEqualTo(300);
            assertThat(context.getBean(InternalTokenProvider.class))
                    .isInstanceOf(StaticInternalTokenProvider.class);
            assertThat(context.getBean(InternalTokenVerifier.class).knownKids()).isEmpty();
        });
    }

    @Test
    void springBindsEd25519ModeAndIssuesTokens() {
        KeyMaterial material = newMaterial();
        contextRunner
                .withPropertyValues(
                        "internal.ai.token.mode=ed25519",
                        "internal.ai.token.private-key=" + material.privateBase64(),
                        "internal.ai.token.kid=k1",
                        "internal.ai.token.ttl-seconds=120")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InternalTokenProperties.class).mode())
                            .isEqualTo(InternalTokenMode.ED25519);
                    assertThat(context.getBean(InternalTokenProvider.class))
                            .isInstanceOf(Ed25519InternalTokenProvider.class);
                    assertThat(context.getBean(InternalTokenVerifier.class).knownKids())
                            .containsExactly("k1");
                    String token = context.getBean(InternalTokenProvider.class).token();
                    assertThat(token).startsWith("eyJ");
                });
    }

    @Test
    void springStartupFailsForEd25519WithoutPrivateKeyAndNeverEchoesMaterial() {
        contextRunner
                .withPropertyValues("internal.ai.token.mode=ed25519")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String messages = messagesOf(context.getStartupFailure());
                    assertThat(messages).contains("internal.ai.token.private-key");
                });
    }

    @Test
    void springStartupFailsForUnknownMode() {
        contextRunner
                .withPropertyValues("internal.ai.token.mode=jwt")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            builder.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return builder.toString();
    }

    private static RerankProperties rerank(String serviceToken) {
        return new RerankProperties(RerankMode.JAVA, "http://127.0.0.1:1", 3000,
                Duration.ofSeconds(2), serviceToken, 20, 8, 1.0, "");
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
