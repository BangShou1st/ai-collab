package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.policy.AgentLoopGuard;
import com.shitulelv.aicollab.agent.domain.policy.AgentToolPolicy;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.*;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentWorker {
    private final AgentRepository repository;
    private final ChatModelGateway model;
    private final AgentDecisionParser parser;
    private final AgentPromptFactory prompts;
    private final AgentToolRegistry tools;
    private final ObjectMapper json;
    private final AgentApprovalService approvals;
    private final AgentToolPolicy toolPolicy = new AgentToolPolicy();
    private final AgentLoopGuard loopGuard = new AgentLoopGuard();

    @Autowired
    public AgentWorker(
            AgentRepository repository, ChatModelGateway model,
            AgentDecisionParser parser, AgentPromptFactory prompts,
            AgentToolRegistry tools, ObjectMapper json, AgentApprovalService approvals) {
        this.repository = repository;
        this.model = model;
        this.parser = parser;
        this.prompts = prompts;
        this.tools = tools;
        this.json = json;
        this.approvals = approvals;
    }

    AgentWorker(
            AgentRepository repository, ChatModelGateway model,
            AgentDecisionParser parser, AgentPromptFactory prompts,
            AgentToolRegistry tools, ObjectMapper json) {
        this(repository, model, parser, prompts, tools, json, null);
    }

    public AgentWorkerOutcome process(ClaimedAgentRun claimed) {
        AgentRunView run = repository.findRun(claimed.projectId(), claimed.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        AgentToolContext context = new AgentToolContext(
                run.id(), run.projectId(), run.requesterId(),
                run.role(), run.scheduled(), run.depth());
        if (run.stepsUsed() >= run.maxSteps()
                || run.inputTokensUsed() >= run.maxInputTokens()
                || run.outputTokensUsed() >= run.maxOutputTokens()) {
            repository.recordBudgetExceeded(run);
            return outcome(AgentRunStatus.BUDGET_EXCEEDED, null, "AGENT_BUDGET_EXCEEDED");
        }

        ChatCompletionResult completion;
        java.util.List<AgentStepView> steps =
                repository.listSteps(run.projectId(), run.id());
        String systemPrompt = prompts.systemPrompt(tools.definitionsFor(context), run.role());
        String userPrompt = prompts.userPrompt(
                run.goal(),
                repository.listMessages(run.projectId(), run.sessionId(), 100),
                steps);
        try {
            completion = model.complete(new ChatCompletionCommand(
                    systemPrompt, userPrompt,
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                    ModelPurpose.AGENT, null, java.util.List.of()));
        } catch (BusinessException failure) {
            boolean retryable = failure.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT
                    || failure.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR;
            repository.recordFailure(run, failure.getErrorCode().name(), retryable);
            return outcome(
                    retryable ? AgentRunStatus.FAILED_RETRYABLE : AgentRunStatus.FAILED,
                    null, failure.getErrorCode().name());
        }

        int input = completion.promptTokens() == null
                ? estimatedTokens(systemPrompt + userPrompt) : completion.promptTokens();
        int output = completion.completionTokens() == null
                ? estimatedTokens(completion.content()) : completion.completionTokens();
        if (completion.promptTokens() == null || completion.completionTokens() == null) {
            completion = new ChatCompletionResult(
                    completion.content(), completion.provider(), completion.model(),
                    input, output, completion.latencyMs());
        }
        if (run.stepsUsed() + 1 > run.maxSteps()
                || run.inputTokensUsed() + input > run.maxInputTokens()
                || run.outputTokensUsed() + output > run.maxOutputTokens()) {
            repository.recordBudgetExceeded(run, completion);
            return outcome(AgentRunStatus.BUDGET_EXCEEDED, null, "AGENT_BUDGET_EXCEEDED");
        }

        AgentDecision decision;
        try {
            decision = parser.parse(completion.content(), run.correctionAttempted());
        } catch (IllegalArgumentException invalid) {
            repository.recordInvalidDecision(run, completion, invalid.getMessage());
            return outcome(
                    run.correctionAttempted() ? AgentRunStatus.FAILED : AgentRunStatus.QUEUED,
                    null, "AGENT_INVALID_DECISION");
        }

        if (decision instanceof AgentDecision.FinalAnswer answer) {
            repository.recordFinal(run, completion, answer);
            return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, answer.answer(), null, null);
        }
        if (decision instanceof AgentDecision.CallTool call) {
            if (loopGuard.hasNoProgress(steps, call)) {
                repository.recordDecisionFailure(
                        run, completion, "AGENT_NO_PROGRESS",
                        "相同工具和参数连续返回相同结果");
                return outcome(AgentRunStatus.FAILED, call.tool(), "AGENT_NO_PROGRESS");
            }
            try {
                AgentTool tool = tools.find(call.tool())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "未注册 Agent 工具: " + call.tool()));
                toolPolicy.requireAllowed(tool, context);
                if (run.toolCallsUsed() >= run.maxToolCalls()) {
                    repository.recordBudgetExceeded(run);
                    return outcome(
                            AgentRunStatus.BUDGET_EXCEEDED, call.tool(), "AGENT_BUDGET_EXCEEDED");
                }
                if (tool instanceof ApprovalWriteAgentTool writeTool) {
                    if (approvals == null) throw new IllegalStateException("审批服务未配置");
                    approvals.propose(run, completion, call, context, writeTool);
                    return outcome(AgentRunStatus.WAITING_FOR_APPROVAL, call.tool(), null);
                }
                AgentToolResult result = tool.execute(context, call.arguments());
                repository.recordToolResult(run, completion, call, json.valueToTree(result));
                return outcome(AgentRunStatus.QUEUED, call.tool(), null);
            } catch (RuntimeException failure) {
                repository.recordDecisionFailure(
                        run, completion, "AGENT_TOOL_EXECUTION_FAILED", failure.getMessage());
                return outcome(
                        AgentRunStatus.FAILED, call.tool(), "AGENT_TOOL_EXECUTION_FAILED");
            }
        }
        if (decision instanceof AgentDecision.Delegate delegate) {
            if (!java.util.Set.of(
                    "KNOWLEDGE_RESEARCHER", "PROGRESS_ANALYST", "RISK_REVIEWER")
                    .contains(delegate.role())
                    || run.depth() != 0 || run.childrenUsed() >= run.maxChildren()) {
                repository.recordDecisionFailure(
                        run, completion, "AGENT_DELEGATION_FORBIDDEN", "Delegation is not allowed");
                return outcome(
                        AgentRunStatus.FAILED, delegate.role(), "AGENT_DELEGATION_FORBIDDEN");
            }
            try {
                repository.recordDelegation(run, completion, delegate);
                return outcome(AgentRunStatus.CREATED, delegate.role(), null);
            } catch (RuntimeException failure) {
                repository.recordDecisionFailure(
                        run, completion, "AGENT_DELEGATION_FAILED", failure.getMessage());
                return outcome(
                        AgentRunStatus.FAILED, delegate.role(), "AGENT_DELEGATION_FAILED");
            }
        }
        repository.recordDecisionFailure(
                run, completion, "AGENT_INVALID_DECISION", "Unsupported decision");
        return outcome(AgentRunStatus.FAILED, null, "AGENT_INVALID_DECISION");
    }

    private static AgentWorkerOutcome outcome(
            AgentRunStatus status, String toolName, String error) {
        return new AgentWorkerOutcome(status, null, toolName, error);
    }

    private static int estimatedTokens(String value) {
        int count = value == null ? 0 : value.codePointCount(0, value.length());
        return Math.max(1, (count + 2) / 3);
    }
}
