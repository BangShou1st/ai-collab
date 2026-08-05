package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent Worker。负责预检查和委托 Runtime Coordinator 处理所有正常 Agent Run。
 * <p>
 * Worker 不再包含旧的工具调用循环（legacy 或 native）。
 * 所有模型交互、工具执行和状态推进统一由 {@link AgentRuntimeCoordinator} 处理。
 */
@Service
public class AgentWorker {
    private final AgentRepository repository;
    private final AgentRuntimeCoordinator coordinator;
    private final AgentEventService events;
    private final ObjectMapper json;

    public AgentWorker(AgentRepository repository, AgentRuntimeCoordinator coordinator) {
        this(repository, coordinator, null, null);
    }

    @Autowired
    public AgentWorker(
            AgentRepository repository,
            AgentRuntimeCoordinator coordinator,
            AgentEventService events,
            ObjectMapper json) {
        if (coordinator == null) {
            throw new IllegalArgumentException("AgentRuntimeCoordinator 不能为 null");
        }
        this.repository = repository;
        this.coordinator = coordinator;
        this.events = events;
        this.json = json;
    }

    public AgentWorkerOutcome process(ClaimedAgentRun claimed) {
        AgentRunView run = repository.findRun(claimed.projectId(), claimed.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        // 预算检查
        if (run.stepsUsed() >= run.maxSteps()
                || run.inputTokensUsed() >= run.maxInputTokens()
                || run.outputTokensUsed() >= run.maxOutputTokens()) {
            repository.recordBudgetExceeded(run);
            if (events != null && json != null) {
                events.append(run.projectId(), run.id(), AgentEventType.RUN_BUDGET_EXCEEDED,
                        json.createObjectNode()
                                .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                                .put("errorCode", "AGENT_BUDGET_EXCEEDED"));
            }
            return new AgentWorkerOutcome(AgentRunStatus.BUDGET_EXCEEDED, null, null, "AGENT_BUDGET_EXCEEDED");
        }

        // 委托 Runtime Coordinator 处理
        try {
            return coordinator.advance(run);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) {
                repository.recordCanceled(run);
                return new AgentWorkerOutcome(AgentRunStatus.CANCELED, null, null, "RUN_CANCELLED");
            }
            throw e;
        }
    }
}
