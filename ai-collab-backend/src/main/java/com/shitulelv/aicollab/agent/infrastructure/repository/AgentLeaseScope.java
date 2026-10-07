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
        var rows = jdbc.queryForList("SELECT claim_version,cancel_requested_at,lease_expires_at > now() AS valid FROM agent_run WHERE project_id=? AND id=? FOR UPDATE", projectId, runId);
        if (rows.isEmpty() || !epoch.equals(rows.getFirst().get("claim_version")) || !Boolean.TRUE.equals(rows.getFirst().get("valid")))
            throw new IllegalStateException("Agent worker lease has expired or been taken over");
        if (!allowCancel && rows.getFirst().get("cancel_requested_at") != null)
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
    }
    @Override public void close() { EPOCH.remove(); }
}
