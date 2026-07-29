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
        return repository.claimNext(workerId, OffsetDateTime.now(ZoneOffset.UTC), lease);
    }
}
