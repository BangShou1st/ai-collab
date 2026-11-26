package com.shitulelv.aicollab.internal.ai.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code internal.ai.token.*} 配置：内部 AI 调用只使用 Ed25519 短令牌。
 *
 * <p>{@code privateKey} 为启动必填项，缺失或非法由 {@link InternalTokenConfiguration} 在启动阶段
 * 直接失败。TTL 收敛到 60..900 秒、时钟偏移收敛到 0..300 秒。</p>
 *
 * <p><b>敏感信息</b>：{@code privateKey} 与 {@code publicKeys} 的值不得进入日志、错误消息或
 * 指标标签；{@link #toString()} 只输出非敏感字段。</p>
 */
@ConfigurationProperties("internal.ai.token")
public record InternalTokenProperties(
        String privateKey,
        String kid,
        int ttlSeconds,
        int clockSkewSeconds,
        String publicKeys) {

    /** TTL 下限（秒）。 */
    public static final int MIN_TTL_SECONDS = 60;

    /** TTL 上限（秒）。 */
    public static final int MAX_TTL_SECONDS = 900;

    /** 时钟偏移上限（秒）。 */
    public static final int MAX_CLOCK_SKEW_SECONDS = 300;

    /** 默认签名密钥标识。 */
    public static final String DEFAULT_KID = "k1";

    @ConstructorBinding
    public InternalTokenProperties(
            @DefaultValue("") String privateKey,
            @DefaultValue(DEFAULT_KID) String kid,
            @DefaultValue("300") int ttlSeconds,
            @DefaultValue("60") int clockSkewSeconds,
            @DefaultValue("") String publicKeys) {
        this.privateKey = privateKey == null ? "" : privateKey.trim();
        this.kid = kid == null || kid.isBlank() ? DEFAULT_KID : kid.trim();
        this.publicKeys = publicKeys == null ? "" : publicKeys.trim();
        this.ttlSeconds = Math.min(Math.max(ttlSeconds, MIN_TTL_SECONDS), MAX_TTL_SECONDS);
        this.clockSkewSeconds = Math.min(Math.max(clockSkewSeconds, 0), MAX_CLOCK_SKEW_SECONDS);
    }

    @Override
    public String toString() {
        return "InternalTokenProperties[kid=" + kid + ", ttlSeconds=" + ttlSeconds
                + ", clockSkewSeconds=" + clockSkewSeconds + ", privateKey=<redacted>, publicKeys=<redacted>]";
    }
}
