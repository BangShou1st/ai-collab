package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class AgentRecoveryJob {
    private final AgentRepository repository;

    public AgentRecoveryJob(AgentRepository repository) {
        this.repository = repository;
    }

    public Optional<ClaimedAgentRun> claim(String workerId, Duration lease) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120
                || lease == null || lease.isNegative() || lease.isZero()
                || lease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Agent worker 或租约无效");
        }
        var claimed = repository.claimNext(workerId, OffsetDateTime.now(ZoneOffset.UTC), lease);
        // 接管运行即原 worker 已越过 stale 边界：其未结算的模型调用身份行结果真实不可知，
        // 显式收口为 0/0 + UNKNOWN 终态，不长期停留在"仍在调用中"，也不虚构消耗。
        claimed.ifPresent(run -> repository.closeUnresolvedModelCalls(run.id()));
        return claimed;
    }
}
