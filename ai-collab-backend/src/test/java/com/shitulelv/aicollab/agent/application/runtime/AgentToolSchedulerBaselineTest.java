package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** S0 runs against the unchanged production scheduler before API migration. */
class AgentToolSchedulerBaselineTest {
    static AgentToolContext context(UUID user, UUID run) {
        return new AgentToolContext(run, UUID.randomUUID(), user, "MEMBER", false, 0);
    }

    @Test void collisionAndWorkerStarvation() throws Exception {
        LegacyToolScheduler scheduler = new LegacyToolScheduler();
        UUID a = new UUID(0, 0), b = new UUID(1, 1);
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 16; i++) {
                scheduler.executor().submit(() -> {
                    try (var ignored = scheduler.acquire(context(a, a))) {
                        started.countDown(); release.await();
                    } catch (Exception ignored) { }
                });
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> independent = scheduler.executor().submit(() -> {
                try (var ignored = scheduler.acquire(context(b, b))) { return true; }
            });
            assertThatThrownTimeout(independent);
            long waiting = Thread.getAllStackTraces().entrySet().stream()
                    .filter(e -> e.getKey().getName().equals("agent-tool"))
                    .filter(e -> Arrays.stream(e.getValue()).anyMatch(s -> s.getClassName().equals(Semaphore.class.getName())))
                    .count();
            assertThat(waiting).isEqualTo(14);
            int rejected = 0;
            for (int i = 0; i < 64; i++) {
                try { scheduler.executor().submit(() -> {}); }
                catch (RejectedExecutionException busy) { rejected++; }
            }
            assertThat(rejected).isEqualTo(1);
            System.out.printf("S0 collision=true activeTools=2 semaphoreWaitingWorkers=%d executorQueued=64 rejected=%d independent=TIMEOUT%n", waiting, rejected);
        } finally { release.countDown(); scheduler.close(); }
    }

    @Test void sameUserRunWaitersConsumeUserPermits() throws Exception {
        LegacyToolScheduler scheduler = new LegacyToolScheduler();
        var hot = context(new UUID(0,10), new UUID(0,20));
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            for (int i=0;i<4;i++) scheduler.executor().submit(() -> {
                try(var ignored=scheduler.acquire(hot)) { started.countDown(); release.await(); }
                catch(Exception ignored) { }
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var field = LegacyToolScheduler.class.getDeclaredField("runs"); field.setAccessible(true);
            Semaphore[] runs = (Semaphore[]) field.get(scheduler);
            Semaphore runGate = runs[Math.floorMod(hot.runId().hashCode(), runs.length)];
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> runGate.getQueueLength() == 2);
            // Two running + two waiting on runGate consume all four user permits.
            Future<?> sibling = scheduler.executor().submit(() -> {
                try(var ignored=scheduler.acquire(context(hot.userId(),new UUID(0,21)))) { return true; }
            });
            assertThatThrownTimeout(sibling);
            Future<?> otherUser = scheduler.executor().submit(() -> {
                try(var ignored=scheduler.acquire(context(new UUID(0,11),new UUID(0,22)))) { return true; }
            });
            assertThat(otherUser.get(5,TimeUnit.SECONDS)).isEqualTo(true);
            System.out.println("S0 sameUser activeTools=2 siblingRun=TIMEOUT unrelatedUser=ADMITTED (user permits held by run waiters)");
        } finally { release.countDown(); scheduler.close(); }
    }
    private static void assertThatThrownTimeout(Future<?> future) throws Exception {
        try { future.get(200, TimeUnit.MILLISECONDS); throw new AssertionError("unexpected admission"); }
        catch (TimeoutException expected) { /* deterministic gate remains closed */ }
    }
}
