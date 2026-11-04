package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
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
 * 同步任务作用域内的<b>有界租约续期与所有权判定</b>。
 *
 * <p>单次同步可能包含多次飞书分页列目录与逐文档内容拉取（单请求超时 30 秒），
 * 执行时间很容易超过 15 分钟租约。若不续期，下一次触发会把仍在运行的合法任务
 * 当作过期回收，产生第二个并发 Worker。复用 {@code AgentLeaseRenewer} 的成熟模式：
 * 每个任务在自己的生命周期内启动一个 daemon 定时器，按短周期把租约推到
 * "当前时刻 + 15 分钟"，随任务结束立即关闭——崩溃后仍按既有短租约窗口被回收，
 * 不引入常驻调度器，也不会出现任务结束后继续续租。</p>
 *
 * <p>所有权判定：{@link ConnectorSyncJobMapper#renewLease} 只会更新
 * {@code status='RUNNING'} 的行。返回 0 即任务已被回收（FAILED）、完成或删除，
 * Worker 必须停止产生新的业务写入；这同时是 Fencing 检查的数据来源。
 * 续期本身是单行 UPDATE 短语句，不在飞书网络请求期间持有事务或行锁。</p>
 *
 * <p>续期调用抛异常（瞬时数据库故障）不判定失权：下个周期重试，数据库长时间
 * 不可用时 scan 内部的既有 Mapper 调用会先一步让任务失败；只有"确凿返回 0"
 * 才代表所有权丢失，避免瞬时抖动误杀健康同步。</p>
 */
public final class ConnectorSyncLeaseGuard implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncLeaseGuard.class);

    /** 续期周期：明显短于 15 分钟租约窗口，保证至少一次续期在过期前完成。 */
    static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofMinutes(3);

    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> task;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean lostOwnership = new AtomicBoolean(false);

    private ConnectorSyncLeaseGuard(ScheduledExecutorService timer, ScheduledFuture<?> task) {
        this.timer = timer;
        this.task = task;
    }

    /**
     * 为指定任务启动续期心跳。生产代码使用默认周期，测试可注入更短周期。
     */
    public static ConnectorSyncLeaseGuard start(ConnectorSyncJobMapper jobs, UUID jobId,
                                                ConnectorSyncMetrics metrics) {
        return start(jobs, jobId, metrics, DEFAULT_RENEW_INTERVAL);
    }

    public static ConnectorSyncLeaseGuard start(ConnectorSyncJobMapper jobs, UUID jobId,
                                                ConnectorSyncMetrics metrics, Duration interval) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connector-sync-lease-" + jobId);
            thread.setDaemon(true);
            return thread;
        });
        ConnectorSyncLeaseGuard guard = new ConnectorSyncLeaseGuard(timer, null);
        AtomicBoolean lost = guard.lostOwnership;
        ScheduledFuture<?> task = timer.scheduleWithFixedDelay(() -> {
            if (guard.stopped.get()) return;
            try {
                if (jobs.renewLease(jobId) != 1) {
                    lost.set(true);
                    timer.shutdownNow();
                    log.warn("同步任务租约续期失败：任务已不属于本 Worker，jobId={}", jobId);
                }
            } catch (RuntimeException failure) {
                metrics.leaseRenewFailed();
                log.debug("租约续期暂时失败，下一周期重试: jobId={}, {}", jobId, failure.toString());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        guard.task = task;
        return guard;
    }

    /** 是否仍持有任务所有权（仅在心跳确凿判定失权后变为 false）。 */
    public boolean holdsOwnership() {
        return !lostOwnership.get();
    }

    @Override
    public void close() {
        stopped.set(true);
        if (task != null) task.cancel(false);
        // 任务结束（正常/异常/失权）立即停止续期：不泄漏定时线程，也不再延长租约。
        timer.shutdownNow();
    }

    /** Worker 失去所有权后用于中止同步的内部信号。 */
    static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("同步任务租约已丢失，所有权已被回收");
        }
    }
}
