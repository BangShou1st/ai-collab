package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.shitulelv.aicollab.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/**
 * Claim epoch fences old workers even when they refresh the run's optimistic version.
 *
 * <p>claim_version 在每次 {@code claimNext} 时递增，因此"当前持有租约的 worker"等价于
 * {@code claim_version == 本 worker 领取时看到的 version}。长请求续租必须复用这一事实，
 * 不能另建一套 owner 标记。</p>
 */
public final class AgentLeaseScope implements AutoCloseable {
    private static final ThreadLocal<Integer> EPOCH = new ThreadLocal<>();
    public AgentLeaseScope(int epoch) { EPOCH.set(epoch); }

    /** 当前线程所属 claim 的 epoch（未在 claim 内时为 null）。续租用它做 fencing。 */
    public static Integer currentEpoch() { return EPOCH.get(); }

    public static void verify(JdbcTemplate jdbc, UUID projectId, UUID runId, boolean allowCancel) {
        Integer epoch = EPOCH.get();
        if (epoch == null) return; // HTTP commands still enforce their status/version conditions.
        var rows = jdbc.queryForList("""
                SELECT claim_version,cancel_requested_at,status,children_pending,
                  lease_expires_at > now() AS valid
                FROM agent_run WHERE project_id=? AND id=? FOR UPDATE
                """, projectId, runId);
        if (rows.isEmpty() || !epoch.equals(rows.getFirst().get("claim_version")))
            throw new IllegalStateException("Agent worker lease has expired or been taken over");
        // V82 并行 fan-out 等待态：委派受理后父运行保持 RUNNING 但租约立即释放
        // （lease_expires_at=now()），"等待子运行"由 children_pending>0 表达，且
        // claimNext 排除这类运行（不存在接管竞争，epoch 仍唯一标识本 claim）——
        // 同一 fan-out 批次内后续委派的受理与拒绝落库需要继续在本 claim 内完成，
        // 因此等待态下"租约已过期"不构成 fencing 失败。
        boolean waitingChildren = "RUNNING".equals(rows.getFirst().get("status"))
                && ((Number) rows.getFirst().get("children_pending")).intValue() > 0;
        if (!Boolean.TRUE.equals(rows.getFirst().get("valid")) && !waitingChildren)
            throw new IllegalStateException("Agent worker lease has expired or been taken over");
        if (!allowCancel && rows.getFirst().get("cancel_requested_at") != null)
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
    }
    @Override public void close() { EPOCH.remove(); }
}
