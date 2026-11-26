package com.shitulelv.aicollab.internal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

/**
 * 测试专用 Ed25519 令牌材料：进程内随机生成的密钥对（TEST ONLY，绝不落盘、绝不用于生产）。
 *
 * <p>内部 AI 端点只接受 Ed25519 短令牌；需要"已鉴权请求"的测试统一从这里取签发器、
 * 验签器与过滤器，避免每个测试类各自拼装。</p>
 */
public final class InternalAiTestTokens {

    /** 令牌 callerUserId 声明的固定测试值。 */
    public static final UUID CALLER_USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    public static final String KID = "k1";

    private static final KeyPair KEY_PAIR = generate();

    private InternalAiTestTokens() {
    }

    public static InternalTokenIssuer issuer() {
        return new InternalTokenIssuer(KEY_PAIR.getPrivate(), KID, 300);
    }

    public static InternalTokenVerifier verifier() {
        return new InternalTokenVerifier(Map.of(KID, KEY_PAIR.getPublic()), 60);
    }

    /** 带 {@link #CALLER_USER_ID} 声明、scope 含 {@code model-call} 的服务令牌。 */
    public static String callerToken() {
        return issuer().issueServiceTokenForCaller(CALLER_USER_ID, null);
    }

    /** 不带 callerUserId 声明的服务令牌（用于验证身份解析 fail closed）。 */
    public static String serviceTokenWithoutCaller() {
        return issuer().issueServiceToken();
    }

    public static InternalAiTokenFilter filter(ObjectMapper objectMapper) {
        return new InternalAiTokenFilter(verifier(), objectMapper);
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK 不支持 Ed25519", unavailable);
        }
    }
}
