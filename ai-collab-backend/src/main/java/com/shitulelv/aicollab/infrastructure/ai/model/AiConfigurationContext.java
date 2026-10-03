package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;

/** Invocation-scoped trusted routing, never populated from model or HTTP request data. */
public final class AiConfigurationContext implements AutoCloseable {
    private static final ThreadLocal<UserAiProvider> CURRENT = new ThreadLocal<>();
    private final UserAiProvider previous;
    public AiConfigurationContext(UserAiProvider snapshot) { previous = CURRENT.get(); CURRENT.set(snapshot); }
    public static UserAiProvider current() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
