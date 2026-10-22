package com.shitulelv.aicollab.agent.infrastructure.tool;

import java.util.concurrent.atomic.AtomicInteger;

/** Per-scenario model-call and wall-clock guard, testable without contacting a provider. */
final class RealAgentLoopBudget {
    private final int maxModelCalls;
    private final long deadlineNanos;
    private final AtomicInteger modelCalls = new AtomicInteger();

    RealAgentLoopBudget(long startedNanos, long timeoutNanos, int maxModelCalls) {
        if (timeoutNanos <= 0 || maxModelCalls <= 0) {
            throw new IllegalArgumentException("scenario limits must be positive");
        }
        this.deadlineNanos = startedNanos + timeoutNanos;
        this.maxModelCalls = maxModelCalls;
    }

    long beginModelCall(long nowNanos) {
        long remaining = deadlineNanos - nowNanos;
        if (remaining <= 0) throw new LimitExceededException("scenario wall-clock limit exceeded");
        if (modelCalls.get() >= maxModelCalls) {
            throw new LimitExceededException("scenario model-call limit exceeded");
        }
        modelCalls.incrementAndGet();
        return remaining;
    }

    long remainingNanos(long nowNanos) {
        long remaining = deadlineNanos - nowNanos;
        if (remaining <= 0) throw new LimitExceededException("scenario wall-clock limit exceeded");
        return remaining;
    }

    boolean expired(long nowNanos) {
        return nowNanos >= deadlineNanos;
    }

    int modelCalls() {
        return modelCalls.get();
    }

    static final class LimitExceededException extends RuntimeException {
        LimitExceededException(String message) {
            super(message);
        }
    }
}
