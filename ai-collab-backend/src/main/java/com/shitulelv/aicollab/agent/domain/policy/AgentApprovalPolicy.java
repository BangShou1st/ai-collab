package com.shitulelv.aicollab.agent.domain.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

public final class AgentApprovalPolicy {
    public String nonceHash(String nonce) {
        if (nonce == null || nonce.isBlank() || nonce.length() > 200) {
            throw new IllegalArgumentException("审批 nonce 无效");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public boolean matchesNonce(String expectedHash, String nonce) {
        if (expectedHash == null || nonce == null) return false;
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                nonceHash(nonce).getBytes(StandardCharsets.US_ASCII));
    }

    public void requirePending(
            String status, OffsetDateTime expiresAt, OffsetDateTime now) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("审批已处理");
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("审批已过期");
        }
    }
}
