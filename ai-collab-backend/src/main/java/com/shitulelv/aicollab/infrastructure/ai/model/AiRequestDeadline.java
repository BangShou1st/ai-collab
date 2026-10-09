package com.shitulelv.aicollab.infrastructure.ai.model;
import java.time.Duration;
/** Caller deadline bounds a model request, including response body reading. */
public final class AiRequestDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
    public void limit(Duration remaining) { DEADLINE.set(System.nanoTime()+Math.max(0,remaining.toNanos())); }
    public static long timeoutMillis(Duration transportLimit) {
        Long deadline=DEADLINE.get();
        return deadline==null ? transportLimit.toMillis() : Math.max(1,Math.min(transportLimit.toMillis(),(deadline-System.nanoTime())/1_000_000));
    }
    @Override public void close() { DEADLINE.remove(); }
}
