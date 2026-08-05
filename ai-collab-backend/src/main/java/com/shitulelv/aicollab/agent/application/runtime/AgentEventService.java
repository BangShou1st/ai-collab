package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class AgentEventService {
    private final AgentEventRepository repository;
    private final AgentEventStreamService streams;

    public AgentEventService(AgentEventRepository repository, AgentEventStreamService streams) {
        this.repository = repository;
        this.streams = streams;
    }

    public AgentRunEventView append(
            UUID projectId, UUID runId, AgentEventType type, JsonNode safePayload) {
        AgentRunEventView event = repository.append(projectId, runId, type, safePayload);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    streams.publish(event);
                }
            });
        } else {
            streams.publish(event);
        }
        return event;
    }

    public AgentRunEventView appendIfAbsent(
            UUID projectId, UUID runId, AgentEventType type, JsonNode safePayload) {
        if (repository.exists(projectId, runId, type)) return null;
        return append(projectId, runId, type, safePayload);
    }
}
