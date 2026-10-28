package com.shitulelv.aicollab.agent.infrastructure.diagnostics;
import org.slf4j.MDC;
import java.util.*;
/** Explicit background correlation; never copies HTTP credentials. */
public final class AgentDiagnosticScope implements AutoCloseable {
    private static final ThreadLocal<Map<String,Long>> TIMINGS = new ThreadLocal<>();
    private final Map<String,String> previous = MDC.getCopyOfContextMap();
    private final Map<String,Long> previousTimings = TIMINGS.get();
    public AgentDiagnosticScope(UUID run, Integer claim, UUID invocation) {
        MDC.clear();
        MDC.put("agentRunId", run.toString());
        if (claim != null) MDC.put("agentClaimVersion", claim.toString());
        if (invocation != null) MDC.put("agentInvocationId", invocation.toString());
        TIMINGS.set(new HashMap<>());
    }
    public static void submitted(String id) {
        if (id != null && TIMINGS.get() != null) TIMINGS.get().put(id, System.nanoTime());
    }
    public static long elapsed(String id) {
        Long start = TIMINGS.get() == null ? null : TIMINGS.get().get(id);
        return start == null ? -1 : Math.max(0, System.nanoTime()-start);
    }
    @Override public void close() {
        MDC.clear();
        if (previous != null) MDC.setContextMap(previous);
        if (previousTimings == null) TIMINGS.remove(); else TIMINGS.set(previousTimings);
    }
}
