package com.shitulelv.aicollab.agent.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * agent_memory.verification_state 来源验证状态(AC07-P1,与现有 ACTIVE/DISABLED 正交)。
 * <p>设计红线:legacy 记录默认 UNVERIFIED,不得静默升级;VERIFIED 仅可由管理员显式设置;
 * CONFLICTED/SOURCE_REVOKED 为系统派生状态,不允许经 API 直接写入。</p>
 */
public enum AgentMemoryVerificationState {
    VERIFIED,
    UNVERIFIED,
    STALE,
    CONFLICTED,
    SOURCE_REVOKED;

    /** 召回安全:VERIFIED 与 UNVERIFIED(legacy 兼容,如实标注)可召回;其余状态在召回/工具路径过滤。 */
    public boolean recallSafe() {
        return this == VERIFIED || this == UNVERIFIED;
    }

    /** 解析管理员可显式设置的验证状态;CONFLICTED/SOURCE_REVOKED 与未知值返回 empty。 */
    public static Optional<AgentMemoryVerificationState> adminSettable(String raw) {
        if (raw == null) return Optional.empty();
        try {
            AgentMemoryVerificationState state = valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return (state == VERIFIED || state == STALE || state == UNVERIFIED)
                    ? Optional.of(state) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
