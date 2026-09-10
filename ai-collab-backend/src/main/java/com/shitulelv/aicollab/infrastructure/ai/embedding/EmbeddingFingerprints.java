package com.shitulelv.aicollab.infrastructure.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Embedding 语义指纹：sha256(provider|model|dimensions)，与 V43 回填表达式一致。
 * 指纹不同即不同向量空间，绝不能混合检索。
 */
public final class EmbeddingFingerprints {
    private EmbeddingFingerprints() {
    }

    public static String fingerprint(String provider, String model, int dimensions) {
        try {
            String raw = provider + "|" + model + "|" + dimensions;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint embedding configuration", exception);
        }
    }
}
