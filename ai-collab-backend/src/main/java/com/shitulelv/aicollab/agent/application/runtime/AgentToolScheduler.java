package com.shitulelv.aicollab.agent.application.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** Shared bounded scheduling. Run batch limits bound each user's outstanding work. */
@Component
public class AgentToolScheduler {
    private final Semaphore[] users = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new Semaphore(4, true)).toArray(Semaphore[]::new);
    private final Semaphore[] runs = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> new Semaphore(2, true)).toArray(Semaphore[]::new);
    AutoCloseable acquire(com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context) throws InterruptedException {
        Semaphore user = users[Math.floorMod(context.userId().hashCode(), users.length)];
        Semaphore run = runs[Math.floorMod(context.runId().hashCode(), runs.length)];
        user.acquire();
        try { run.acquire(); } catch (InterruptedException failure) { user.release(); throw failure; }
        return () -> { run.release(); user.release(); };
    }
    private final ExecutorService executor = new ThreadPoolExecutor(16, 16, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "agent-tool");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    ExecutorService executor() { return executor; }
    @PreDestroy void close() { executor.shutdownNow(); }
}
