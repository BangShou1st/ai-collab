package com.shitulelv.aicollab.internal.ai.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部 AI 令牌装配：只支持 Ed25519 短令牌。
 *
 * <p>{@code internal.ai.token.private-key} 是启动必填项：缺失或非法直接让应用<b>启动失败</b>，
 * 不存在任何静态令牌降级路径。</p>
 *
 * <p>Java 同时是签发方与验签方（worker 会把同一令牌回传给 {@code /internal/ai/**}）：
 * 验签公钥集合 = 配置的 {@code public-keys} + 由私钥派生的当前 {@code kid} 公钥。
 * 派生保证轮换时不会出现"Java 发了新 kid 但自己验不过"的锁死。</p>
 *
 * <p><b>密钥卫生</b>：所有启动失败消息只包含属性名/格式期望，绝不包含私钥、公钥或 base64 内容；
 * 解析失败时也不把底层异常挂成 cause，避免其消息或栈帧携带密钥片段。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalTokenProperties.class)
public class InternalTokenConfiguration {

    @Bean
    public InternalTokenIssuer internalTokenIssuer(InternalTokenProperties tokenProperties) {
        return new InternalTokenIssuer(requireSigningKey(tokenProperties), tokenProperties.kid(),
                tokenProperties.ttlSeconds());
    }

    @Bean
    public InternalTokenProvider internalTokenProvider(InternalTokenIssuer internalTokenIssuer) {
        return new Ed25519InternalTokenProvider(internalTokenIssuer);
    }

    @Bean
    public InternalTokenVerifier internalTokenVerifier(InternalTokenProperties tokenProperties) {
        return createVerifier(tokenProperties);
    }

    /** 由配置构建验签器（测试与非 Spring 装配复用同一校验逻辑）。 */
    public static InternalTokenVerifier createVerifier(InternalTokenProperties tokenProperties) {
        PrivateKey signingKey = requireSigningKey(tokenProperties);
        Map<String, PublicKey> keys = parsePublicKeys(tokenProperties.publicKeys());
        PublicKey derived = Ed25519Keys.derivePublicKey(signingKey);
        PublicKey configured = keys.get(tokenProperties.kid());
        if (configured != null && !Arrays.equals(configured.getEncoded(), derived.getEncoded())) {
            throw new IllegalStateException("internal.ai.token.public-keys 中 kid="
                    + tokenProperties.kid() + " 与私钥派生公钥不一致");
        }
        keys.put(tokenProperties.kid(), derived);
        return new InternalTokenVerifier(keys, tokenProperties.clockSkewSeconds());
    }

    /**
     * 读取并校验私钥；缺失或非法 → 启动失败。
     *
     * <p>异常消息刻意只描述"哪个配置项、期望什么格式"，不含任何密钥内容。</p>
     */
    public static PrivateKey requireSigningKey(InternalTokenProperties tokenProperties) {
        if (tokenProperties == null || tokenProperties.privateKey().isBlank()) {
            throw new IllegalStateException(
                    "缺少 internal.ai.token.private-key（INTERNAL_AI_ED25519_PRIVATE_KEY，PKCS#8 DER base64）");
        }
        try {
            return Ed25519Keys.parsePrivateKey(tokenProperties.privateKey());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "internal.ai.token.private-key 非法：需为 PKCS#8 DER base64 的 Ed25519 私钥");
        }
    }

    /**
     * 解析 {@code kid=SPKI DER base64,kid=...}。
     *
     * <p>格式/重复/非法密钥都属于配置错误：启动失败，避免带着空公钥环 fail closed 到所有请求 401。
     */
    private static Map<String, PublicKey> parsePublicKeys(String raw) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return keys;
        }
        for (String entry : raw.split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalStateException(
                        "internal.ai.token.public-keys 格式非法：应为 kid=SPKI-DER-base64[,kid=...]");
            }
            String kid = value.substring(0, separator).trim();
            String encoded = value.substring(separator + 1).trim();
            if (kid.isEmpty() || encoded.isEmpty()) {
                throw new IllegalStateException(
                        "internal.ai.token.public-keys 格式非法：kid 与公钥都不能为空");
            }
            if (keys.containsKey(kid)) {
                throw new IllegalStateException("internal.ai.token.public-keys 含重复 kid=" + kid);
            }
            try {
                keys.put(kid, Ed25519Keys.parsePublicKey(encoded));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "internal.ai.token.public-keys 中 kid=" + kid + " 不是合法的 SPKI DER Ed25519 公钥");
            }
        }
        return keys;
    }
}
