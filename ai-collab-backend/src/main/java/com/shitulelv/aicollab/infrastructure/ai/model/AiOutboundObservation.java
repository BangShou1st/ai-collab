package com.shitulelv.aicollab.infrastructure.ai.model;

/** A bounded request-local transport signal. No IDs, credentials or model content. */
public final class AiOutboundObservation implements AutoCloseable {
    private static final ThreadLocal<AiOutboundObservation> CURRENT = new ThreadLocal<>();
    private final AiOutboundObservation previous = CURRENT.get();
    private final java.util.concurrent.atomic.AtomicLong started = new java.util.concurrent.atomic.AtomicLong();

    public AiOutboundObservation() { CURRENT.set(this); }
    public static AiOutboundObservation current() { return CURRENT.get(); }
    public static void markStarted(AiOutboundObservation observation) {
        if (observation != null) observation.started.compareAndSet(0, System.nanoTime());
    }
    /** Shared-client request preparation rejected locally before sending. */
    public static void markNotStarted(AiOutboundObservation observation) {
        if (observation != null) observation.started.set(0);
    }
    public boolean wasStarted() { return started.get() != 0; }
    public long elapsed() { return Math.max(0, System.nanoTime() - started.get()); }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
