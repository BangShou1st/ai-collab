package com.shitulelv.aicollab.agent.infrastructure.decide;

/**
 * decide 端点的四决策词表（W4 冻结契约 §2）。
 *
 * <p>词表之外的取值一律是契约违规，不得猜测执行。</p>
 */
public enum DecideDecision {

    CALL_TOOL,
    FINAL,
    ASK_USER,
    NO_OP;

    /** 严格解析线上取值：大小写不敏感；未知值返回 null，由调用方按契约违规处理。 */
    public static DecideDecision fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (DecideDecision decision : values()) {
            if (decision.name().equalsIgnoreCase(normalized)) {
                return decision;
            }
        }
        return null;
    }
}
