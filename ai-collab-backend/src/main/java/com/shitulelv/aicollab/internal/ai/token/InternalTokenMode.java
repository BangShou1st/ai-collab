package com.shitulelv.aicollab.internal.ai.token;

import java.util.Locale;

/**
 * 内部 AI 令牌模式（冻结契约 §2.2）。
 *
 * <p>{@link #STATIC} 是生产默认：与 W3 完全一致（{@code rerank.service-token} 静态令牌）。
 * {@link #ED25519} 由 Java 签发短令牌、两端都只接受短令牌；{@link #DUAL} 只用于轮换过渡：
 * 签发走 Ed25519，验签同时接受 Ed25519 与静态令牌（见 runbook §4）。</p>
 *
 * <p>未知取值在<b>绑定阶段</b>直接失败（与 {@code RerankMode} 同一约定），不静默回退静态。</p>
 */
public enum InternalTokenMode {

    STATIC,
    ED25519,
    DUAL;

    /** 解析配置值（大小写不敏感）；未知取值抛 {@link IllegalStateException}，不打印配置内容。 */
    public static InternalTokenMode from(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "static" -> STATIC;
            case "ed25519" -> ED25519;
            case "dual" -> DUAL;
            default -> throw new IllegalStateException(
                    "internal.ai.token.mode 只能是 static / ed25519 / dual");
        };
    }

    /** 是否需要 Ed25519 私钥（{@code ed25519}/{@code dual}）。 */
    public boolean requiresPrivateKey() {
        return this != STATIC;
    }

    /** 是否接受 Ed25519 短令牌。 */
    public boolean acceptsEd25519() {
        return this != STATIC;
    }

    /** 是否接受静态令牌（{@code static}/{@code dual}）。 */
    public boolean acceptsStatic() {
        return this != ED25519;
    }
}
