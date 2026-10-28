package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** Single-JVM atomic admission ledger with bounded two-level round robin. */
@Component
public class AgentToolScheduler {
    private final Object lock = new Object();
    private final int globalLimit, userLimit, runLimit, pendingLimit;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "agent-tool-deadline"); t.setDaemon(true); return t;
    });
    private final MeterRegistry metrics;
    private final LinkedHashMap<UUID, UserState> users = new LinkedHashMap<>();
    private final Set<Ticket<?>> admitted = new HashSet<>();
    private int active, pending;
    private boolean closed;

    public AgentToolScheduler() { this(new SimpleMeterRegistry()); }
    @Autowired public AgentToolScheduler(MeterRegistry metrics) {
        this(16, 4, 2, 64, pool(16), metrics);
    }
    AgentToolScheduler(int global, int user, int run, int queue, ExecutorService executor, MeterRegistry metrics) {
        if (global < 1 || user < 1 || run < 1 || queue < 1) throw new IllegalArgumentException("positive limits required");
        globalLimit = global; userLimit = user; runLimit = run; pendingLimit = queue;
        this.executor = executor; this.metrics = metrics;
        deadlines.setRemoveOnCancelPolicy(true);
        metrics.gauge("agent.tool.scheduler.active", this, s -> s.snapshot().active());
        metrics.gauge("agent.tool.scheduler.pending", this, s -> s.snapshot().pending());
    }
    static ExecutorService pool(int size) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(size), r -> {
            Thread t = new Thread(r, "agent-tool"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    <T> Ticket<T> submit(AgentToolContext context, long deadlineNanos, Callable<T> task) {
        Objects.requireNonNull(context.userId()); Objects.requireNonNull(context.runId()); Objects.requireNonNull(task);
        synchronized (lock) {
            if (closed) throw reject("shutdown");
            drain();
            UserState user = users.get(context.userId());
            RunState run = user == null ? null : user.runs.get(context.runId());
            boolean ready = active < globalLimit && (user == null || user.active < userLimit)
                    && (run == null || run.active < runLimit);
            if (!ready && pending == pendingLimit) throw reject("queue_full");
            user = users.computeIfAbsent(context.userId(), UserState::new);
            run = user.runs.computeIfAbsent(context.runId(), RunState::new);
            Ticket<T> ticket = new Ticket<>(user, run, deadlineNanos, task);
            run.waiting.addLast(ticket); pending++;
            ticket.alarm = deadlines.schedule(ticket::expireBeforeStart, Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            drain(); return ticket;
        }
    }
    private RejectedExecutionException reject(String reason) {
        metrics.counter("agent.tool.scheduler.rejected", "reason", reason).increment();
        return new RejectedExecutionException("Tool scheduler: " + reason);
    }
    /** Scan is bounded by active + pending identities. Never wait for a permit on a worker. */
    private void drain() {
        if (closed) return;
        while (active < globalLimit) {
            Ticket<?> next = null;
            for (UserState user : List.copyOf(users.values())) {
                for (RunState run : List.copyOf(user.runs.values())) {
                    while (!run.waiting.isEmpty() && run.waiting.peekFirst().expired())
                        finishNotStarted(run.waiting.peekFirst(), Phase.EXPIRED_NOT_STARTED, new TimeoutException());
                    if (user.active < userLimit && run.active < runLimit && !run.waiting.isEmpty()) {
                        next = run.waiting.peekFirst();
                        user.runs.remove(run.id); user.runs.put(run.id, run);
                        users.remove(user.id); users.put(user.id, user); break;
                    }
                }
                if (next != null) break;
            }
            if (next == null) return;
            next.run.waiting.removeFirst(); pending--;
            next.phase = Phase.RESERVED; active++; next.user.active++; next.run.active++; admitted.add(next);
            try { executor.execute(next); }
            catch (RejectedExecutionException failure) {
                next.phase = Phase.REJECTED; next.cancelAlarm(); release(next);
                next.result.completeExceptionally(reject("executor_rejected"));
            }
        }
    }
    private void release(Ticket<?> ticket) {
        admitted.remove(ticket); active--; ticket.user.active--; ticket.run.active--; cleanup(ticket.user, ticket.run);
    }
    private void cleanup(UserState user, RunState run) {
        if (run.active == 0 && run.waiting.isEmpty()) user.runs.remove(run.id);
        if (user.active == 0 && user.runs.isEmpty()) users.remove(user.id);
    }
    private void finishNotStarted(Ticket<?> ticket, Phase phase, Throwable failure) {
        if (ticket.phase == Phase.PENDING) {
            ticket.run.waiting.remove(ticket); pending--; cleanup(ticket.user, ticket.run);
        } else if (ticket.phase == Phase.RESERVED) {
            if (executor instanceof ThreadPoolExecutor pool) pool.remove(ticket);
            release(ticket);
        } else return;
        ticket.phase = phase; ticket.cancelAlarm();
        if (phase == Phase.EXPIRED_NOT_STARTED) timeoutMetric("not_started");
        if (failure == null) ticket.result.cancel(false); else ticket.result.completeExceptionally(failure);
    }
    private void timeoutMetric(String phase) { metrics.counter("agent.tool.scheduler.timeout", "phase", phase).increment(); }
    enum Phase { PENDING, RESERVED, RUNNING, COMPLETED, REJECTED, EXPIRED_NOT_STARTED, CANCELLED_NOT_STARTED, CANCEL_REQUESTED_RUNNING }
    record Snapshot(int active, int pending, int users, int runs, int alarms) { }
    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(active, pending, users.size(), users.values().stream().mapToInt(u -> u.runs.size()).sum(), deadlines.getQueue().size());
        }
    }
    final class Ticket<T> implements Future<T>, Runnable {
        private final UserState user;
        private final RunState run;
        private final long submitted = System.nanoTime(), deadline;
        private final Callable<T> task;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Phase phase = Phase.PENDING;
        private Thread runner;
        private ScheduledFuture<?> alarm;
        private boolean started, timedOut;
        private Ticket(UserState user, RunState run, long deadline, Callable<T> task) {
            this.user = user; this.run = run; this.deadline = deadline; this.task = task;
        }
        private boolean expired() { return deadline - System.nanoTime() <= 0; }
        private void cancelAlarm() { if (alarm != null) alarm.cancel(false); }
        private void expireBeforeStart() {
            synchronized (lock) { finishNotStarted(this, Phase.EXPIRED_NOT_STARTED, new TimeoutException()); drain(); }
        }
        Phase phase() { synchronized (lock) { return phase; } }
        /** Returns whether execution began; cancellation and start are serialized by the same lock. */
        boolean timeout() {
            synchronized (lock) {
                if (!timedOut && phase != Phase.EXPIRED_NOT_STARTED) {
                    timedOut = true; timeoutMetric(started ? "inflight" : "not_started");
                }
                cancel(true); return started;
            }
        }
        @Override public void run() {
            long start;
            synchronized (lock) {
                if (phase != Phase.RESERVED) return;
                if (expired()) { finishNotStarted(this, Phase.EXPIRED_NOT_STARTED, new TimeoutException()); drain(); return; }
                phase = Phase.RUNNING; started = true; runner = Thread.currentThread(); cancelAlarm(); start = System.nanoTime();
                metrics.timer("agent.tool.scheduler.queue.wait").record(start - submitted, TimeUnit.NANOSECONDS);
            }
            T value = null; Throwable failure = null;
            try { value = task.call(); } catch (Throwable thrown) { failure = thrown; }
            finally {
                synchronized (lock) {
                    metrics.timer("agent.tool.scheduler.execution").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                    runner = null; phase = Phase.COMPLETED;
                    // Only the actual callable exit releases RUNNING reservations, even after cancellation.
                    release(this); drain();
                    if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                }
            }
        }
        @Override public boolean cancel(boolean interrupt) {
            synchronized (lock) {
                if (result.isDone()) return false;
                boolean running = phase == Phase.RUNNING || phase == Phase.CANCEL_REQUESTED_RUNNING;
                metrics.counter("agent.tool.scheduler.cancelled", "phase", running ? "running_interrupt_requested" : "not_started").increment();
                if (running) {
                    phase = Phase.CANCEL_REQUESTED_RUNNING; result.cancel(false);
                    // Lock prevents runner reuse between observing its identity and issuing the interrupt.
                    if (interrupt && runner != null) runner.interrupt();
                } else finishNotStarted(this, Phase.CANCELLED_NOT_STARTED, null);
                drain(); return true;
            }
        }
        @Override public boolean isCancelled() { return result.isCancelled(); }
        @Override public boolean isDone() { return result.isDone(); }
        @Override public T get() throws InterruptedException, ExecutionException { return result.get(); }
        @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException { return result.get(timeout, unit); }
    }
    private static final class UserState {
        final UUID id; int active; final LinkedHashMap<UUID, RunState> runs = new LinkedHashMap<>();
        UserState(UUID id) { this.id = id; }
    }
    private static final class RunState {
        final UUID id; int active; final Deque<Ticket<?>> waiting = new ArrayDeque<>();
        RunState(UUID id) { this.id = id; }
    }
    @PreDestroy void close() {
        synchronized (lock) {
            if (closed) return; closed = true;
            for (UserState user : List.copyOf(users.values()))
                for (RunState run : List.copyOf(user.runs.values()))
                    for (Ticket<?> ticket : List.copyOf(run.waiting)) ticket.cancel(false);
            for (Ticket<?> ticket : List.copyOf(admitted)) ticket.cancel(true);
            deadlines.shutdownNow(); executor.shutdownNow();
        }
    }
}
