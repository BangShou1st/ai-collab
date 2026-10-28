package com.shitulelv.aicollab.agent.domain.tool;

/** Trusted host capability, independent of model arguments and remote readOnlyHint. */
public enum AgentToolExecutionSafety {
    SAFE_READ,
    IDEMPOTENT_OR_RECONCILABLE,
    UNCONFIRMABLE_SIDE_EFFECT
}
