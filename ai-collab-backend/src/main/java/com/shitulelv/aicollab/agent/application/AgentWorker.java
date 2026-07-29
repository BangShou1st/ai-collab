package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.policy.AgentToolPolicy;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.springframework.stereotype.Service;

@Service
public class AgentWorker {
    private final AgentRepository repository;
    private final ChatModelGateway model;
    private final AgentDecisionParser parser;
    private final AgentPromptFactory prompts;
    private final AgentToolRegistry tools;
    private final ObjectMapper json;
    private final AgentToolPolicy toolPolicy = new AgentToolPolicy();

    public AgentWorker(
            AgentRepository repository,
            ChatModelGateway model,
            AgentDecisionParser parser,
            AgentPromptFactory prompts,
            AgentToolRegistry tools,
            ObjectMapper json) {
        this.repository = repository;
        this.model = model;
        this.parser = parser;
        this.prompts = prompts;
        this.tools = tools;
        this.json = json;
    }

    public AgentWorkerOutcome process(ClaimedAgentRun claimed) {
        AgentRunView run = repository.findRun(claimed.projectId(), claimed.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        if (run.stepsUsed() >= run.maxSteps()
                || run.inputTokensUsed() >= run.maxInputTokens()
                || run.outputTokensUsed() >= run.maxOutputTokens()) {
            repository.recordBudgetExceeded(run);
            return new AgentWorkerOutcome(
                    AgentRunStatus.BUDGET_EXCEEDED, null, null, "AGENT_BUDGET_EXCEEDED");
        }

        ChatCompletionResult completion;
        try {
            completion = model.complete(new ChatCompletionCommand(
                    prompts.systemPrompt(tools.names(), run.role()),
                    prompts.userPrompt(
                            run.goal(),
                            repository.listMessages(run.projectId(), run.sessionId(), 100),
                            repository.listSteps(run.projectId(), run.id())),
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT));
        } catch (BusinessException failure) {
            boolean retryable = failure.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT
                    || failure.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR;
            repository.recordFailure(run, failure.getErrorCode().name(), retryable);
            return new AgentWorkerOutcome(
                    retryable ? AgentRunStatus.FAILED_RETRYABLE : AgentRunStatus.FAILED,
                    null, null, failure.getErrorCode().name());
        }

        AgentDecision decision;
        try {
            decision = parser.parse(completion.content(), run.correctionAttempted());
        } catch (IllegalArgumentException invalid) {
            repository.recordInvalidDecision(run, completion, invalid.getMessage());
            return new AgentWorkerOutcome(
                    run.correctionAttempted() ? AgentRunStatus.FAILED : AgentRunStatus.QUEUED,
                    null, null, "AGENT_INVALID_DECISION");
        }

        if (decision instanceof AgentDecision.FinalAnswer answer) {
            repository.recordFinal(run, completion, answer);
            return new AgentWorkerOutcome(
                    AgentRunStatus.SUCCEEDED, answer.answer(), null, null);
        }
        if (decision instanceof AgentDecision.CallTool call) {
            AgentTool tool = tools.find(call.tool())
                    .orElseThrow(() -> new IllegalArgumentException("未注册 Agent 工具: " + call.tool()));
            AgentToolContext context = new AgentToolContext(
                    run.id(), run.projectId(), run.requesterId(),
                    run.role(), run.scheduled(), run.depth());
            toolPolicy.requireAllowed(tool, context);
            if (run.toolCallsUsed() >= run.maxToolCalls()) {
                repository.recordBudgetExceeded(run);
                return new AgentWorkerOutcome(
                        AgentRunStatus.BUDGET_EXCEEDED, null, call.tool(), "AGENT_BUDGET_EXCEEDED");
            }
            AgentToolResult result = tool.execute(context, call.arguments());
            repository.recordToolResult(run, completion, call, json.valueToTree(result));
            return new AgentWorkerOutcome(AgentRunStatus.QUEUED, null, call.tool(), null);
        }

        repository.recordFailure(run, "AGENT_DELEGATION_NOT_AVAILABLE", false);
        return new AgentWorkerOutcome(
                AgentRunStatus.FAILED, null, null, "AGENT_DELEGATION_NOT_AVAILABLE");
    }
}
