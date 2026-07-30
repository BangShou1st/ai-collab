package com.shitulelv.aicollab.agent.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true")
public class AgentRuntimeJob {
    private final AgentRecoveryJob recovery;
    private final AgentWorker worker;
    private final com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository approvals;
    private final String workerId = "embedded-" + java.util.UUID.randomUUID();

    public AgentRuntimeJob(
            AgentRecoveryJob recovery, AgentWorker worker,
            com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository approvals) {
        this.recovery = recovery;
        this.worker = worker;
        this.approvals = approvals;
    }

    @Scheduled(fixedDelayString = "${agent.worker-delay-ms:1000}")
    public void tick() {
        approvals.expirePending(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        recovery.claim(workerId, Duration.ofMinutes(2)).ifPresent(worker::process);
    }
}
