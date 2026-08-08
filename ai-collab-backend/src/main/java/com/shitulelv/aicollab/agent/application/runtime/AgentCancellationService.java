package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 取消服务。检查取消标记并在合适时机中止运行。
 */
@Service
public class AgentCancellationService {
    private final AgentRepository repository;

    public AgentCancellationService(AgentRepository repository) {
        this.repository = repository;
    }

    /**
     * 检查是否已请求取消。如果已取消，抛出异常中止运行。
     * 必须在以下位置调用：
     * - 模型调用前
     * - 模型返回后
     * - 每个 Tool Call 前
     * - Tool 执行后
     * - Replan 前
     * - 创建审批前
     */
    public void throwIfRequested(AgentRunView run) {
        if (run.status() == AgentRunStatus.CANCELED
                || repository.isCancelRequested(run.projectId(), run.id())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
        }
    }

    /**
     * 检查 Run 是否可取消。
     */
    public boolean isCancelable(AgentRunStatus status) {
        return status == AgentRunStatus.QUEUED
                || status == AgentRunStatus.RUNNING
                || status == AgentRunStatus.WAITING_FOR_APPROVAL
                || status == AgentRunStatus.WAITING_FOR_USER_INPUT
                || status == AgentRunStatus.FAILED_RETRYABLE;
    }
}
