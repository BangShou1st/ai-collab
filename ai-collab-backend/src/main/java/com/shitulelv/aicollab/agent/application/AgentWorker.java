package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
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
    @Autowired private com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService cancellation;

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
        try (var registration = cancellation == null ? null : cancellation.register(claimed.id());
             var scope = new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(claimed.version())) {
            return processOwned(claimed);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private AgentWorkerOutcome processOwned(ClaimedAgentRun claimed) {
        AgentRunView run = repository.findRun(claimed.projectId(), claimed.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        // 暂停意图优先于一切推进动作：接管带暂停意图的过期运行只确认 PAUSED，
        // 不开始任何新动作；此前用户已看到的暂停意图在此收口。
        if (repository.pauseIfRequested(run)) {
            return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
        }

        // 预算前置检查回答"是否准入下一次请求"；消费已保存结果（pendingModelTurn）
        // 不消耗新一轮请求，不受该预留检查影响——否则接管会把有效最终文本丢成
        // BUDGET_EXCEEDED。真实输入实际超限仍由协调器 afterResponseSaved 如实结算。
        //
        // v2（累计 token 只统计）下累计输入/输出上限为 null：这两个条件不参与准入，
        // 不得把 null 读成 0（那会把每次 v2 运行立刻判成额度耗尽）。
        // 累计上限只对 v1 生效，v1 沿用既有语义（判据是预算语义的 used 列，与 actual 区分）。
        boolean hasSavedResult = repository.pendingModelTurn(run).isPresent();
        boolean tokenBudgetExhausted = run.enforcesCumulativeTokenLimits()
                && (AgentResourcePolicy.inputExhausted(run.contextPolicyVersion(),
                        run.maxInputTokens(), null, run.inputTokensUsed())
                    || AgentResourcePolicy.outputExhausted(run.contextPolicyVersion(),
                        run.maxOutputTokens(), null, run.outputTokensUsed()));
        if (!hasSavedResult
                && (run.stepsUsed() >= run.maxSteps() || tokenBudgetExhausted)) {
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
