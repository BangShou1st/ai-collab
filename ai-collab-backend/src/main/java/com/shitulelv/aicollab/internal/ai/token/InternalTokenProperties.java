package com.shitulelv.aicollab.internal.ai.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code internal.ai.token.*} 配置（冻结契约 §2.2）。
 *
 * <p><b>默认必须行为不变</b>：{@code mode=static}、私钥/公钥为空，未配置时与 W3 完全一致。
 * TTL 收敛到 60..900 秒、时钟偏移收敛到 0..300 秒（配置错误不应阻止启动）；但
 * {@code ed25519}/{@code dual} 模式的私钥缺失/非法由 {@link InternalTokenConfiguration}
 * 在启动阶段直接失败。</p>
 *
 * <p><b>敏感信息</b>：{@code privateKey} 与 {@code publicKeys} 的值不得进入日志、错误消息或
 * 指标标签；本类的访问器天然不打印内容，配置绑定失败信息也由框架给出属性名。</p>
 */
@ConfigurationProperties("internal.ai.token")
public record InternalTokenProperties(
        @DefaultValue("static") InternalTokenMode mode,
        @DefaultValue("") String privateKey,
        @DefaultValue("k1") String kid,
        @DefaultValue("300") int ttlSeconds,
        @DefaultValue("60") int clockSkewSeconds,
        @DefaultValue("") String publicKeys) {

    /** TTL 下限（秒）。 */
    public static final int MIN_TTL_SECONDS = 60;

    /** TTL 上限（秒）。 */
    public static final int MAX_TTL_SECONDS = 900;

    /** 时钟偏移上限（秒）。 */
    public static final int MAX_CLOCK_SKEW_SECONDS = 300;

    /** 默认签名密钥标识。 */
    public static final String DEFAULT_KID = "k1";

    public InternalTokenProperties {
        if (mode == null) {
            mode = InternalTokenMode.STATIC;
        }
        privateKey = privateKey == null ? "" : privateKey.trim();
        kid = kid == null || kid.isBlank() ? DEFAULT_KID : kid.trim();
        publicKeys = publicKeys == null ? "" : publicKeys.trim();
        if (ttlSeconds < MIN_TTL_SECONDS) {
            ttlSeconds = MIN_TTL_SECONDS;
        } else if (ttlSeconds > MAX_TTL_SECONDS) {
            ttlSeconds = MAX_TTL_SECONDS;
        }
        if (clockSkewSeconds < 0) {
            clockSkewSeconds = 0;
        } else if (clockSkewSeconds > MAX_CLOCK_SKEW_SECONDS) {
            clockSkewSeconds = MAX_CLOCK_SKEW_SECONDS;
        }
    }

    /** 默认配置（static、无私钥/公钥），供不依赖 Spring Binder 的代码与测试使用。 */
    public static InternalTokenProperties defaults() {
        return new InternalTokenProperties(InternalTokenMode.STATIC, "", DEFAULT_KID, 300, 60, "");
    }
}
