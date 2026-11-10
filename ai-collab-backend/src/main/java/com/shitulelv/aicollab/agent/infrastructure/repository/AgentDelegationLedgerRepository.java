package com.shitulelv.aicollab.agent.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 委派防重台账（{@code agent_delegation_ledger}，V81）的持久化。
 *
 * <p>职责：受理委派时插入 RUNNING 记录（与子运行创建同一事务）、按会话+哈希
 * 统计现存非 CANCELED 记录（准入判重）、子运行终态收口时同步台账状态。
 * 判重的纯函数规则在 {@link com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard}。</p>
 */
@Repository
public class AgentDelegationLedgerRepository {

    private final JdbcTemplate jdbc;

    public AgentDelegationLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 同一会话下同一 objective 哈希的现存非 CANCELED 台账记录数。
     * CANCELED 的委派视为未发生，不阻止再次委派（用户主动取消不是重复委派）。
     */
    public int countActiveByObjective(UUID sessionId, String objectiveHash) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_delegation_ledger
                WHERE session_id=? AND objective_hash=? AND status<>'CANCELED'
                """, Integer.class, sessionId, objectiveHash);
        return count == null ? 0 : count;
    }

    /** 受理委派时插入一条 RUNNING 台账记录（调用方保证在创建子运行的同一事务内）。 */
    public void insertRunning(UUID sessionId, UUID parentRunId, UUID childRunId, String objectiveHash) {
        jdbc.update("""
                INSERT INTO agent_delegation_ledger(
                  session_id,parent_run_id,child_run_id,objective_hash,status)
                VALUES (?,?,?,?,'RUNNING')
                """, sessionId, parentRunId, childRunId, objectiveHash);
    }

    /**
     * 子运行终态收口：把该子运行的台账记录更新为对应终态。
     * 只收口 RUNNING 记录（幂等：重复收口 no-op）；终态之间不迁移。
     */
    public void closeForChild(UUID childRunId, String terminalStatus) {
        jdbc.update("""
                UPDATE agent_delegation_ledger SET status=?
                WHERE child_run_id=? AND status='RUNNING'
                """, terminalStatus, childRunId);
    }
}
