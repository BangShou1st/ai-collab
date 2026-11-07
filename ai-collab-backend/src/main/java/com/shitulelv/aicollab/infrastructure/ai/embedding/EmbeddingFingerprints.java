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

    /**
     * Gemini 原生协议指纹扩展：在 provider|model|baseUrl|apiPath|dimensions 基础上追加
     * 任务类型配对（RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY）与协议版本分量。
     * 仅用于 provider=GEMINI 的新配置；旧 3 元指纹表达式与既有代次数据完全不受影响。
     */
    public static String geminiFingerprint(String model, String baseUrl, String apiPath,
            int dimensions, String taskTypePair) {
        return fingerprint("GEMINI",
                model + "|" + baseUrl + "|" + apiPath + "|taskTypePair=" + taskTypePair
                        + "|protocol=gemini-native-v1",
                dimensions);
    }
}
