package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AgentToolSchedulerTest {
    static AgentToolContext ctx(UUID user, UUID run) { return AgentToolSchedulerBaselineTest.context(user, run); }
    static AgentToolContext unique() { return ctx(UUID.randomUUID(), UUID.randomUUID()); }
    static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(30); }
    static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
    static void empty(AgentToolScheduler s) {
        assertThat(s.snapshot()).isEqualTo(new AgentToolScheduler.Snapshot(0, 0, 0, 0, 0));
    }
    static final class ManualExecutor extends AbstractExecutorService {
        final Deque<Runnable> work = new ArrayDeque<>(); boolean stopped;
        public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); work.add(task); }
        void next() { work.removeFirst().run(); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; var copy = List.copyOf(work); work.clear(); return copy; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public boolean awaitTermination(long n, TimeUnit u) { return stopped; }
    }
    static AgentToolScheduler scheduler(int global, int user, int run, int queue, ExecutorService e) {
        return new AgentToolScheduler(global, user, run, queue, e, new SimpleMeterRegistry());
    }
    @Test void c01ExactCollisionIsolation() throws Exception {
        var e = new ManualExecutor(); var s = scheduler(16,4,2,64,e);
        UUID a = new UUID(0,0), b = new UUID(1,1);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        try {
            for (UUID user : List.of(a,b)) for (UUID run : List.of(a,b)) for (int i=0;i<2;i++) s.submit(ctx(user,run),deadline(),()->1);
            assertThat(s.snapshot().active()).isEqualTo(8);
            assertThat(s.snapshot().pending()).isZero();
            while (!e.work.isEmpty()) e.next(); empty(s);
        } finally { s.close(); }
    }
    @Test void c02c03AtomicRunAndUserAdmission() {
        var e = new ManualExecutor(); var s = scheduler(16,4,2,64,e); UUID user = UUID.randomUUID();
        try {
            var hot = ctx(user,UUID.randomUUID());
            for(int i=0;i<12;i++) s.submit(hot,deadline(),()->1);
            assertThat(s.snapshot().active()).isEqualTo(2);
            assertThat(e.work).hasSize(2);
            for(int i=0;i<4;i++) s.submit(ctx(user,UUID.randomUUID()),deadline(),()->1);
            assertThat(s.snapshot().active()).isEqualTo(4);
            assertThat(s.snapshot().pending()).isEqualTo(12);
            while(!e.work.isEmpty()) e.next(); empty(s);
        } finally { s.close(); }
    }
    @Test void c04RoundRobinUsersAndRunsWithoutHeadBlocking() {
        var e = new ManualExecutor(); var s = scheduler(1,1,1,64,e); var order = new ArrayList<String>();
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(); var a1=ctx(a,UUID.randomUUID()); var a2=ctx(a,UUID.randomUUID());
        var b1=ctx(b,UUID.randomUUID());
        try {
            s.submit(a1,deadline(),()->order.add("a1-first"));
            s.submit(a1,deadline(),()->order.add("a1-next"));
            s.submit(a1,deadline(),()->order.add("a1-last"));
            s.submit(a2,deadline(),()->order.add("a2"));
            s.submit(b1,deadline(),()->order.add("b1"));
            while(!e.work.isEmpty()) e.next();
            assertThat(order).containsExactly("a1-first","a1-next","b1","a2","a1-last"); empty(s);
        } finally { s.close(); }
    }
    @Test void c05CapacityAndReadyBypassFullPending() {
        var e=new ManualExecutor(); var s=scheduler(16,4,2,64,e); var hot=unique();
        try {
            for(int i=0;i<66;i++) s.submit(hot,deadline(),()->1);
            assertThat(s.snapshot().active()).isEqualTo(2); assertThat(s.snapshot().pending()).isEqualTo(64);
            assertThatThrownBy(()->s.submit(hot,deadline(),()->1)).isInstanceOf(RejectedExecutionException.class);
            for(int i=0;i<14;i++) s.submit(unique(),deadline(),()->1);
            assertThat(s.snapshot().active()).isEqualTo(16);
            assertThatThrownBy(()->s.submit(unique(),deadline(),()->1)).isInstanceOf(RejectedExecutionException.class);
            s.close(); empty(s);
        } finally { s.close(); }
    }
    @Test void c06PendingReservedCancellation() {
        var e=new ManualExecutor(); var s=scheduler(1,1,1,64,e); AtomicInteger calls=new AtomicInteger();
        try {
            var reserved=s.submit(unique(),deadline(),calls::incrementAndGet);
            var pending=s.submit(unique(),deadline(),calls::incrementAndGet);
            assertThat(pending.cancel(true)).isTrue(); assertThat(reserved.cancel(true)).isTrue();
            e.next(); assertThat(calls).hasValue(0); empty(s);
        } finally { s.close(); }
    }
    @Test void c06RunningIgnoresInterruptUntilActualExit() throws Exception {
        var e=AgentToolScheduler.pool(1); var s=scheduler(1,1,1,64,e);
        CountDownLatch began=new CountDownLatch(1), release=new CountDownLatch(1), interrupted=new CountDownLatch(1);
        try {
            var first=s.submit(unique(),deadline(),()->{
                began.countDown(); boolean done=false;
                while(!done) try { release.await(); done=true; } catch(InterruptedException ignored) { interrupted.countDown(); }
                return 1;
            });
            await(began); first.cancel(true); await(interrupted);
            var next=s.submit(unique(),deadline(),()->2);
            assertThat(s.snapshot().active()).isEqualTo(1); assertThat(s.snapshot().pending()).isEqualTo(1);
            assertThat(next.phase()).isEqualTo(AgentToolScheduler.Phase.PENDING);
            release.countDown(); assertThat(next.get(5,TimeUnit.SECONDS)).isEqualTo(2); empty(s);
        } finally { release.countDown(); s.close(); }
    }
    @Test void c06CancelDispatchRace() throws Exception {
        var e=AgentToolScheduler.pool(2); var s=scheduler(2,2,2,64,e);
        try {
            for(int i=0;i<300;i++) {
                var t=s.submit(unique(),deadline(),()->1); t.cancel(true);
            }
            s.submit(unique(),deadline(),()->1).get(5,TimeUnit.SECONDS);
            // Close also joins actual worker exit through the executor termination boundary.
        } finally { s.close(); assertThat(e.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
        empty(s);
    }
    @Test void c07RejectAndShutdown() throws Exception {
        var e=new ManualExecutor(); e.shutdown(); var s=scheduler(1,1,1,64,e);
        try {
            var t=s.submit(unique(),deadline(),()->1);
            assertThatThrownBy(t::get).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RejectedExecutionException.class);
            empty(s); s.close();
            assertThatThrownBy(()->s.submit(unique(),deadline(),()->1)).isInstanceOf(RejectedExecutionException.class);
        } finally { s.close(); }
    }
    @Test void c08ExpiredReservedAndPendingNeverStart() throws Exception {
        var e=new ManualExecutor(); var s=scheduler(1,1,1,64,e); AtomicInteger calls=new AtomicInteger();
        try {
            var reserved=s.submit(unique(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(30),calls::incrementAndGet);
            var pending=s.submit(unique(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(30),calls::incrementAndGet);
            assertThatThrownBy(()->reserved.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            while(!e.work.isEmpty()) e.next(); assertThat(calls).hasValue(0); empty(s);
        } finally { s.close(); }
    }
    @Test void c07ShutdownKeepsRunningCapacityUntilExit() throws Exception {
        var e=AgentToolScheduler.pool(1); var m=new SimpleMeterRegistry();
        var s=new AgentToolScheduler(1,1,1,64,e,m);
        CountDownLatch began=new CountDownLatch(1), release=new CountDownLatch(1), interrupted=new CountDownLatch(1);
        try {
            var first=s.submit(unique(),deadline(),()->{
                began.countDown(); boolean done=false;
                while(!done) try { release.await(); done=true; } catch(InterruptedException ignored) { interrupted.countDown(); }
                return 1;
            });
            await(began);
            assertThat(first.timeout()).isTrue(); await(interrupted);
            var waiting=s.submit(unique(),deadline(),()->2);
            s.close();
            assertThat(first.isCancelled()).isTrue(); assertThat(waiting.isCancelled()).isTrue();
            assertThat(s.snapshot().active()).isEqualTo(1); assertThat(s.snapshot().pending()).isZero();
            assertThat(m.get("agent.tool.scheduler.timeout").tag("phase","inflight").counter().count()).isEqualTo(1);
            assertThatThrownBy(()->s.submit(unique(),deadline(),()->1)).isInstanceOf(RejectedExecutionException.class);
            release.countDown(); assertThat(e.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); empty(s);
        } finally { release.countDown(); s.close(); }
    }
    @Test void c08PendingExpiresWhileCapacityRemainsOccupied() throws Exception {
        var e=new ManualExecutor(); var s=scheduler(1,1,1,64,e);
        try {
            var holder=s.submit(unique(),deadline(),()->1);
            var expired=s.submit(unique(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(30),()->2);
            assertThatThrownBy(()->expired.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            assertThat(expired.phase()).isEqualTo(AgentToolScheduler.Phase.EXPIRED_NOT_STARTED);
            assertThat(s.snapshot().active()).isEqualTo(1); assertThat(s.snapshot().pending()).isZero();
            e.next(); assertThat(holder.get()).isEqualTo(1); empty(s);
        } finally { s.close(); }
    }
    @Test void c09TenThousandIdentitiesReclaimed() throws Exception {
        var e=new ManualExecutor(); var s=scheduler(16,4,2,64,e);
        try {
            for(int i=0;i<10_000;i++) { var t=s.submit(unique(),deadline(),()->1); e.next(); assertThat(t.get()).isEqualTo(1); }
            empty(s);
        } finally { s.close(); }
    }
    @Test void c10MetricsBoundedAndTimeoutCountedOnce() throws Exception {
        var e=new ManualExecutor(); var m=new SimpleMeterRegistry(); var s=new AgentToolScheduler(1,1,1,1,e,m);
        try {
            var first=s.submit(unique(),deadline(),()->1);
            var waiting=s.submit(unique(),deadline(),()->2);
            assertThatThrownBy(()->s.submit(unique(),deadline(),()->1)).isInstanceOf(RejectedExecutionException.class);
            assertThat(m.get("agent.tool.scheduler.pending").gauge().value()).isEqualTo(1);
            waiting.timeout(); e.next(); first.get();
            assertThat(m.get("agent.tool.scheduler.queue.wait").timer().count()).isEqualTo(1);
            assertThat(m.get("agent.tool.scheduler.execution").timer().count()).isEqualTo(1);
            assertThat(m.get("agent.tool.scheduler.timeout").counter().count()).isEqualTo(1);
            assertThat(m.get("agent.tool.scheduler.cancelled").counter().count()).isEqualTo(1);
            assertThat(m.get("agent.tool.scheduler.rejected").counter().count()).isEqualTo(1);
            assertThat(m.getMeters()).allSatisfy(meter->assertThat(meter.getId().getTags()).allSatisfy(tag->
                    assertThat(tag.getKey()).isIn("reason","phase")));
            empty(s);
        } finally { s.close(); }
    }
}
