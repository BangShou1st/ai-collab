package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.UUID;

/** Provider-neutral request correlation. Business layers only know correlationSessionId; Zen transport maps it to x-opencode-session. */
public record AiRequestMetadata(String correlationSessionId) {
    public AiRequestMetadata {
        if (correlationSessionId == null || correlationSessionId.isBlank()) throw new IllegalArgumentException("correlationSessionId required");
        String v = correlationSessionId.strip();
        if (v.contains("\n") || v.contains("\r")) throw new IllegalArgumentException("correlationSessionId must be single-line");
        if (v.length() > 128) throw new IllegalArgumentException("correlationSessionId too long");
        correlationSessionId = v;
    }
    public static AiRequestMetadata fresh() { return new AiRequestMetadata(UUID.randomUUID().toString()); }
    public static AiRequestMetadata of(String id) { return new AiRequestMetadata(id); }
}
