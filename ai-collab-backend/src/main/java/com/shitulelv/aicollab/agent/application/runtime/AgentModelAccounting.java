package com.shitulelv.aicollab.agent.application.runtime;

/** Estimate the actual serialized request when the provider omits usage. */
public final class AgentModelAccounting implements AutoCloseable {
    private static final ThreadLocal<Integer> INPUT=new ThreadLocal<>();
    public AgentModelAccounting() { INPUT.set(0); }
    public static void estimate(int input) { INPUT.set(input); }
    public static int estimatedInput(int fallback) { Integer estimate=INPUT.get(); return estimate==null || estimate==0 ? fallback : estimate; }
    @Override public void close() { INPUT.remove(); }
}
