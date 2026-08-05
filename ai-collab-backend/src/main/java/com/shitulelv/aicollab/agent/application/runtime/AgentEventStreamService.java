package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AgentEventStreamService {
    private static final Set<AgentEventType> TERMINAL = Set.of(
            AgentEventType.RUN_CANCELED,
            AgentEventType.RUN_SUCCEEDED,
            AgentEventType.RUN_FAILED,
            AgentEventType.RUN_BUDGET_EXCEEDED);

    private final ProjectAccessGuard access;
    private final AgentRepository runs;
    private final AgentEventRepository events;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public AgentEventStreamService(
            ProjectAccessGuard access, AgentRepository runs, AgentEventRepository events) {
        this.access = access;
        this.runs = runs;
        this.events = events;
    }

    public SseEmitter subscribe(UUID projectId, UUID runId, UUID userId, long cursor) {
        access.requireMember(projectId, userId);
        runs.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(projectId, runId, emitter, cursor);
        replay(subscription);
        if (!subscription.closed) {
            subscriptions.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>())
                    .add(subscription);
            replay(subscription);
        }
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));
        return emitter;
    }

    public void publish(AgentRunEventView event) {
        for (Subscription subscription : subscriptions.getOrDefault(
                event.runId(), new CopyOnWriteArrayList<>())) {
            if (subscription.projectId.equals(event.projectId())) {
                send(subscription, event);
            }
        }
    }

    @Scheduled(fixedDelayString = "${agent.events.heartbeat-ms:20000}")
    public void heartbeat() {
        subscriptions.values().forEach(list -> list.forEach(subscription -> {
            try {
                subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException failure) {
                remove(subscription);
            }
        }));
    }

    private void replay(Subscription subscription) {
        for (AgentRunEventView event : events.list(
                subscription.projectId, subscription.runId, subscription.lastSequence, 500)) {
            send(subscription, event);
            if (subscription.closed) return;
        }
    }

    private void send(Subscription subscription, AgentRunEventView event) {
        synchronized (subscription) {
            if (subscription.closed || event.sequence() <= subscription.lastSequence) return;
            try {
                subscription.emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type().name())
                        .data(event));
                subscription.lastSequence = event.sequence();
                if (TERMINAL.contains(event.type())) {
                    subscription.closed = true;
                    subscription.emitter.complete();
                    remove(subscription);
                }
            } catch (IOException | IllegalStateException failure) {
                subscription.closed = true;
                remove(subscription);
            }
        }
    }

    private void remove(Subscription subscription) {
        CopyOnWriteArrayList<Subscription> list = subscriptions.get(subscription.runId);
        if (list == null) return;
        list.remove(subscription);
        if (list.isEmpty()) subscriptions.remove(subscription.runId, list);
    }

    private static final class Subscription {
        private final UUID projectId;
        private final UUID runId;
        private final SseEmitter emitter;
        private long lastSequence;
        private boolean closed;

        private Subscription(UUID projectId, UUID runId, SseEmitter emitter, long lastSequence) {
            this.projectId = projectId;
            this.runId = runId;
            this.emitter = emitter;
            this.lastSequence = lastSequence;
        }
    }
}
