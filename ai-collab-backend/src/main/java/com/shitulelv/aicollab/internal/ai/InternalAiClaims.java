package com.shitulelv.aicollab.internal.ai;

import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import jakarta.servlet.http.HttpServletRequest;

import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 过滤器验签通过后的 P1 claim 视图（契约 §1）：{@code uid/pid/purp/tid/ep/scope}。
 *
 * <p>格式非法的字段一律解析为 {@code null}/空集，由调用方按 fail closed 处理；本类不打印任何 claim 值。</p>
 */
public record InternalAiClaims(
        UUID userId,
        UUID projectId,
        Set<String> purposes,
        UUID taskId,
        Long epoch,
        Set<String> scopes) {

    public InternalAiClaims {
        purposes = purposes == null ? Set.of() : Set.copyOf(purposes);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** 从 request attribute 读取；没有已验签 claims 时返回全空视图。 */
    public static InternalAiClaims from(HttpServletRequest request) {
        Object raw = request == null ? null
                : request.getAttribute(InternalAiTokenFilter.VERIFIED_CLAIMS_ATTRIBUTE);
        return raw instanceof Map<?, ?> map ? from(map) : empty();
    }

    public static InternalAiClaims from(Map<?, ?> claims) {
        if (claims == null) {
            return empty();
        }
        return new InternalAiClaims(
                uuid(claims.get(InternalTokenIssuer.CLAIM_UID)),
                uuid(claims.get(InternalTokenIssuer.CLAIM_PID)),
                strings(claims.get(InternalTokenIssuer.CLAIM_PURP)),
                uuid(claims.get(InternalTokenIssuer.CLAIM_TID)),
                integral(claims.get(InternalTokenIssuer.CLAIM_EP)),
                scopes(claims.get("scope")));
    }

    public static InternalAiClaims empty() {
        return new InternalAiClaims(null, null, Set.of(), null, null, Set.of());
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    private static UUID uuid(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Long integral(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof BigInteger big && big.bitLength() < 64) {
            return big.longValue();
        }
        return null;
    }

    private static Set<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                result.add(text.trim());
            }
        }
        return result;
    }

    private static Set<String> scopes(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : text.trim().split("\\s+")) {
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }
}
