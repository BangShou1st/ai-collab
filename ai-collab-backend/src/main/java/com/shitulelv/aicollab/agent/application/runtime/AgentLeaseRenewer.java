package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 长模型请求期间的<b>有界租约续期</b>（设计 3.1 节）。
 *
 * <p>单次模型请求默认放宽到 10 分钟，而 worker claim 租约仍是短窗口（默认 6 分钟）。
 * 若请求返回时租约已过期，另一个 worker 会合法接管，本次合法响应随后被 epoch fencing
 * 拒绝落库——"请求成功但结果丢失"。把租约直接延长到几十分钟又会让崩溃接管退化。
 * 因此这里只做最小有界续租：在请求进行期间按短周期把租约推到"当前时刻 + 一个短窗口"，
 * 崩溃后仍按既有短租约窗口被接管。</p>
 *
 * <p>复用现有机制：续租走 {@link AgentRepository#renewLease}，fencing 走
 * {@link AgentLeaseScope} 的 claim epoch；不新建调度器体系（本类只是请求作用域内的
 * 单线程定时器，随请求关闭而释放）。失去租约、被取消、到限或退出时立即停止续租。</p>
 *
 * <p>不跨 HTTP 持数据库事务锁：每次续租是一个独立的短事务。</p>
 */
final class AgentLeaseRenewer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AgentLeaseRenewer.class);

    /** 续租周期：明显短于租约窗口，保证至少一次续租机会在过期前完成。 */
    private static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofMinutes(2);
    /** 每次续租推到的窗口长度（与 worker claim 的短租约一致，崩溃接管时效不变）。 */
    private static final Duration DEFAULT_LEASE_WINDOW = Duration.ofMinutes(6);

    private final ScheduledExecutorService timer;
    private final ScheduledFuture<?> task;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private AgentLeaseRenewer(
            AgentRepository repository, UUID projectId, UUID runId, Integer claimEpoch,
            ScheduledExecutorService timer, Duration interval, Duration leaseWindow) {
        this.timer = timer;
        // 无 claim epoch（非 claim 上下文）时没有可续的租约：不建定时器，
        // close() 即无操作。绝不能在这里对 null 定时器注册任务。
        if (claimEpoch == null || timer == null) {
            this.task = null;
            this.stopped.set(true);
            return;
        }
        this.task = timer.scheduleWithFixedDelay(() -> {
            if (stopped.get()) return;
            try {
                // epoch 为 null（非 claim 上下文，例如 HTTP 触发的推进）时不续租：
                // 没有 claim 就没有可续的租约，不能凭空延长别人的 claim。
                if (claimEpoch == null) return;
                boolean renewed = repository.renewLease(projectId, runId, claimEpoch, leaseWindow);
                if (!renewed) {
                    // 已失去租约/已取消/状态不符：停止续租，让正常收口尽早发生
                    stopped.set(true);
                    cancel();
                    log.debug("停止租约续期（已失去 claim 或运行已离开运行状态）: run={}", runId);
                }
            } catch (RuntimeException failure) {
                // 续租失败不改业务语义：请求照常返回，由既有租约/epoch 校验决定落库与否
                log.debug("租约续期失败，停止继续尝试: run={}, {}", runId, failure.toString());
                stopped.set(true);
                cancel();
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 为当前线程所属的 claim 启动续租；不在 claim 上下文（无 epoch）时返回关闭即无操作的实例。
     *
     * @param repository 运行仓储
     * @param projectId  项目
     * @param runId      运行
     */
    static AgentLeaseRenewer forCurrentClaim(AgentRepository repository, UUID projectId, UUID runId) {
        return forCurrentClaim(repository, projectId, runId, DEFAULT_RENEW_INTERVAL, DEFAULT_LEASE_WINDOW);
    }

    static AgentLeaseRenewer forCurrentClaim(
            AgentRepository repository, UUID projectId, UUID runId,
            Duration interval, Duration leaseWindow) {
        Integer epoch = AgentLeaseScope.currentEpoch();
        if (epoch == null) {
            return new AgentLeaseRenewer(repository, projectId, runId, null, null, interval, leaseWindow);
        }
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-renew");
            thread.setDaemon(true);
            return thread;
        });
        return new AgentLeaseRenewer(repository, projectId, runId, epoch, timer, interval, leaseWindow);
    }

    /** 是否仍在续租（测试与诊断用）。 */
    boolean active() {
        return !stopped.get() && task != null;
    }

    private void cancel() {
        if (task != null) task.cancel(false);
    }

    @Override
    public void close() {
        stopped.set(true);
        cancel();
        // 退出时停止续租：不泄漏定时任务，也不再延长租约（正常收口会清空租约列）
        if (timer != null) timer.shutdownNow();
    }
}
