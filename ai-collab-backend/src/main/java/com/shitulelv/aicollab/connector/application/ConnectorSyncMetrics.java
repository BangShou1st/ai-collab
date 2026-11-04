package com.shitulelv.aicollab.connector.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 飞书同步任务可靠性指标。
 *
 * <p>刻意保持低基数：所有标签只取枚举值（status），绝不携带 sourceId / projectId /
 * userId / connectionId / jobId 等高基数标识。指标失败不改变业务结果。</p>
 */
@Component
public class ConnectorSyncMetrics {
    private final MeterRegistry registry;

    public ConnectorSyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 派发被线程池拒绝并已完成数据库状态补偿的次数。 */
    public void dispatchRejected() {
        counter("connector.sync.dispatch.rejected");
    }

    /** 租约续期调用本身抛出异常（瞬时数据库故障等），任务尚未判定失权。 */
    public void leaseRenewFailed() {
        counter("connector.sync.lease.renew.failed");
    }

    /** 触发路径发现租约过期并回收 RUNNING 任务的次数。 */
    public void leaseReclaimed() {
        counter("connector.sync.lease.expired.reclaimed");
    }

    /** Worker 检测到失去任务所有权后主动停止执行的次数。 */
    public void ownershipLost() {
        counter("connector.sync.ownership.lost");
    }

    /** 任务最终收口数量，按状态打标（SUCCESS / FAILED / PARTIAL_FAILED）。 */
    public void finished(String status) {
        try {
            registry.counter("connector.sync.finished", "status", status).increment();
        } catch (RuntimeException ignored) {
            // 指标不能影响业务结果。
        }
    }

    private void counter(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标不能影响业务结果。
        }
    }
}
