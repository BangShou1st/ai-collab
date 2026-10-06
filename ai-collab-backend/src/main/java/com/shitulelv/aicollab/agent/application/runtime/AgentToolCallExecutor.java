package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentProposalOutcome;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.*;


import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.domain.tool.ToolArgumentValidator;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行一批模型工具调用：先统一校验，再分区执行（只读并行、写/审批串行）。
 *
 * <p>从 {@link AgentRuntimeCoordinator} 拆出：工具白名单/权限/Schema/循环检测、
 * 提案创建与修订、超时与失败记录都在这里。</p>
 */
public class AgentToolCallExecutor {
    private static final Logger log = LoggerFactory.getLogger(AgentToolCallExecutor.class);
    /** 批次任务的暂停哨兵：意图已落库、本调用未获得执行准入，结果保持 PENDING。 */
    private static final BusinessException PAUSE_ADMISSION =
            new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停");

    private final AgentRepository repository;
    private final AgentToolRegistry tools;
    private final AgentCancellationService cancellation;
    private final AgentLoopGuard loopGuard;
    private final AgentApprovalService approvals;
    private final RoutingAgentModelExecutor modelExecutor;
    private final AgentToolResultSanitizer sanitizer;
    private final ObjectMapper json;
    private final AgentEventService events;
    private final AgentToolScheduler scheduler;

    public AgentToolCallExecutor(
            AgentRepository repository,
            AgentToolRegistry tools,
            AgentCancellationService cancellation,
            AgentLoopGuard loopGuard,
            AgentApprovalService approvals,
            RoutingAgentModelExecutor modelExecutor,
            AgentToolResultSanitizer sanitizer,
            ObjectMapper json,
            AgentEventService events,
            AgentToolScheduler scheduler) {
        this.repository = repository;
        this.tools = tools;
        this.cancellation = cancellation;
        this.loopGuard = loopGuard;
        this.approvals = approvals;
        this.modelExecutor = modelExecutor;
        this.sanitizer = sanitizer;
        this.json = json;
        this.events = events;
        this.scheduler = scheduler;
    }

    AgentWorkerOutcome executeCalls(
            AgentRunView run,
            AgentExecutionContext ctx,
            AgentSkill skill,
            ModelTurnResult turn,
            List<AgentToolDefinition> exposed,
            List<AgentStepView> steps) {
        return executeCalls(run, ctx, skill, turn, exposed, steps, false, false);
    }

    AgentWorkerOutcome executeCalls(
            AgentRunView run,
            AgentExecutionContext ctx,
            AgentSkill skill,
            ModelTurnResult turn,
            List<AgentToolDefinition> exposed,
            List<AgentStepView> steps,
            boolean recoveryBatch) {
        return executeCalls(run, ctx, skill, turn, exposed, steps, recoveryBatch, false);
    }

    /**
     * @param recoveryBatch     true 表示处理已持久化模型轮次的待处理调用
     * @param requestLegacyMode 本次请求准备时解析出的执行模式（仅新轮次调用的校验来源）
     */
    AgentWorkerOutcome executeCalls(
            AgentRunView run,
            AgentExecutionContext ctx,
            AgentSkill skill,
            ModelTurnResult turn,
            List<AgentToolDefinition> exposed,
            List<AgentStepView> steps,
            boolean recoveryBatch,
            boolean requestLegacyMode) {

        List<ModelToolCall> assistantToolCalls = new ArrayList<>(turn.toolCalls());
        java.util.Set<String> callIds = new java.util.HashSet<>();
        if (assistantToolCalls.stream().anyMatch(call -> call.id() == null || call.id().isBlank()
                || !callIds.add(call.id()))) {
            repository.recordFailure(run, "TOOL_CALL_PROTOCOL_INVALID", false);
            return new AgentWorkerOutcome(AgentRunStatus.FAILED, null, null, "TOOL_CALL_PROTOCOL_INVALID");
        }
        AgentToolContext toolCtx = new AgentToolContext(
                run.id(), run.projectId(), run.requesterId(),
                ctx.projectRole(), run.scheduled(), run.depth());

        // ===== 阶段 1：验证所有工具调用（快速，无 I/O） =====
        List<ValidatedToolCall> validated = new ArrayList<>();
        List<JsonNode> recovered = new ArrayList<>();
        boolean proposalSeen = false;
        boolean clarificationSeen=false;
        Boolean parameterCorrection = null;
        for (ModelToolCall toolCall : assistantToolCalls) {
            cancellation.throwIfRequested(run);
            var known = repository.knownInvocationResult(run, toolCall);
            if (known != null && known.isPresent()) { recovered.add(known.get()); continue; }
            ValidatedToolCall vtc = validateToolCall(run, toolCall, ctx, skill, exposed, steps, toolCtx, recoveryBatch, requestLegacyMode);
            if (vtc.error()==null && com.shitulelv.aicollab.agent.infrastructure.tool.RequestUserInputAgentTool.NAME.equals(toolCall.name())) {
                if (clarificationSeen) vtc=new ValidatedToolCall(null,toolCall,createError("CLARIFICATION_BATCH_LIMIT","每批仅允许一次澄清动作"),false);
                clarificationSeen=true;
            }
            if (vtc.error()!=null && "TOOL_ARGUMENT_INVALID".equals(vtc.error().path("error").asText())) {
                if (parameterCorrection==null) parameterCorrection=repository.consumeRecovery(run,"PARAMETER_CORRECTION",1);
                if (!parameterCorrection) vtc=new ValidatedToolCall(null,toolCall,
                        createError("PARAMETER_CORRECTION_EXHAUSTED",vtc.error().path("message").asText()),true);
            }
            if (vtc.writeTool() != null && proposalSeen) {
                vtc = new ValidatedToolCall(null, toolCall,
                        createError("PROPOSAL_BATCH_LIMIT", "每批只允许一个提案，请分轮提交"), false);
            } else if (vtc.writeTool() != null) proposalSeen = true;
            validated.add(vtc);
            if (vtc.error() != null) {
                // 验证失败：记录错误并继续验证下一个
                run = repository.recordToolResult(run, toolCall.name(), input(toolCall), vtc.error(), true);
                emit(run, AgentEventType.TOOL_CALL_FAILED, json.createObjectNode()
                        .put("callId", toolCall.id()).put("toolName", toolCall.name())
                        .put("status", "REJECTED").put("errorCode", vtc.error().path("error").asText()));
                if (vtc.fatal()) {
                    // All calls must receive a disposition before ending the batch.
                }
            }
        }

        // ===== 阶段 2：分区执行 — 只读并行，写/审批串行 =====
        if (validated.stream().anyMatch(ValidatedToolCall::fatal)) {
            for (ValidatedToolCall call : validated) if (call.error() == null) {
                ObjectNode skipped = createError("BATCH_ABORTED", "批次因不可继续的调用被终止");
                skipped.put("status", "SKIPPED");
                run = repository.recordToolResult(run, call.toolCall().name(), input(call.toolCall()), skipped, true);
            }
            String code = validated.stream().filter(ValidatedToolCall::fatal).findFirst().orElseThrow().error().path("error").asText();
            repository.recordFailure(run, code, false);
            return new AgentWorkerOutcome(AgentRunStatus.FAILED, null, null, code);
        }
        List<ValidatedToolCall> readOnlyBatch = new ArrayList<>();
        for (ValidatedToolCall vtc : validated) {
            if (vtc.error() == null && vtc.writeTool() == null && !vtc.tool().writesBusinessData()) readOnlyBatch.add(vtc);
        }
        if (!readOnlyBatch.isEmpty()) {
            BatchOutcome outcome = executeToolBatch(run, readOnlyBatch, toolCtx, ctx.limits());
            if (outcome.paused()) return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
            run = outcome.run();
        }
        readOnlyBatch.clear();
        var clarification = validated.stream().filter(v -> v.error() == null
                && com.shitulelv.aicollab.agent.infrastructure.tool.RequestUserInputAgentTool.NAME.equals(v.toolCall().name())).findFirst();
        var recoveredClarification = recovered.stream().filter(r -> r.path("data").has("questions")).findFirst();
        if (clarification.isPresent() || recoveredClarification.isPresent()) {
            for (ValidatedToolCall call : validated) if(call.error()==null && call.tool() instanceof com.shitulelv.aicollab.agent.domain.tool.ControlledWriteAgentTool)
                run=repository.recordToolResult(run,call.toolCall().name(),input(call.toolCall()),createError("WAITING_FOR_INPUT","等待澄清期间不启动生成"),true);
            for (ValidatedToolCall call : validated) if (call.writeTool() != null) {
                run = repository.recordToolResult(run, call.toolCall().name(), input(call.toolCall()),
                        createError("WAITING_FOR_INPUT", "等待澄清期间不生成提案，请分轮提交"), true);
            }
            StringBuilder questions = new StringBuilder();
            JsonNode items = clarification.isPresent() ? clarification.get().toolCall().arguments().path("questions")
                    : recoveredClarification.orElseThrow().path("data").path("questions");
            items.forEach(q -> questions.append(q.asText()).append('\n'));
            String question = questions.toString().strip();
            repository.recordWaitingForInput(run, question);
            emit(run, AgentEventType.WAITING_FOR_USER_INPUT, json.createObjectNode().put("question", question).put("typed", true));
            return new AgentWorkerOutcome(AgentRunStatus.WAITING_FOR_USER_INPUT, question, null, null);
        }
        var controlled=validated.stream().filter(v->v.error()==null && v.tool() instanceof com.shitulelv.aicollab.agent.domain.tool.ControlledWriteAgentTool).toList();
        if(!controlled.isEmpty()) {
            if(controlled.size()>1 || validated.stream().anyMatch(v->v.writeTool()!=null)) {
                for(var call:validated) if(call.error()==null && (call.writeTool()!=null || call.tool().writesBusinessData()))
                    run=repository.recordToolResult(run,call.toolCall().name(),input(call.toolCall()),createError("WRITE_BATCH_LIMIT","一次仅允许一个规划操作或一个提案，请分轮执行"),true);
            } else {
                BatchOutcome controlledOutcome = executeToolBatch(run,controlled,toolCtx,ctx.limits());
                if (controlledOutcome.paused()) return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
                run = controlledOutcome.run();
                var recorded=repository.listSteps(run.projectId(),run.id());
                boolean accepted=recorded.stream().anyMatch(s->controlled.getFirst().toolCall().name().equals(s.toolName()) && s.output()!=null && s.output().path("data").has("operationId"));
                if(accepted) {
                    String summary="规划操作已受理。后台状态与结果会更新到对应规划卡片；生成完成后请打开规划页审阅并人工确认指定版本。";
                    repository.recordFinal(run,summary,List.of());
                    emit(run,AgentEventType.RUN_SUCCEEDED,json.createObjectNode().put("status","SUCCEEDED"));
                    return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED,summary,null,null);
                }
            }
        }
        for (ValidatedToolCall vtc : validated) {
            if (vtc.error() != null) continue; // 已记录错误，跳过

            if (vtc.writeTool() != null) {
                // 遇到写工具：先 flush 只读批次
                if (!readOnlyBatch.isEmpty()) {
                    BatchOutcome flushed = executeToolBatch(run, readOnlyBatch, toolCtx, ctx.limits());
                    if (flushed.paused()) return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
                    run = flushed.run();
                    readOnlyBatch.clear();
                }
                // 写工具串行执行
                cancellation.throwIfRequested(run);
                // 提案创建是业务受理边界：暂停意图先落库则不创建提案，写调用保持 PENDING
                if (repository.pauseIfRequested(run)) {
                    return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
                }

                // 使用 proposeOrRevise 支持提案修订
                AgentProposalOutcome outcome;
                try {
                    outcome = approvals.proposeOrRevise(
                            run, turn, vtc.toolCall(), toolCtx, vtc.writeTool());
                } catch (BusinessException failure) {
                    if (failure.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) throw failure;
                    // 受理边界内的暂停拒绝（审批/规划服务的行锁检查）：转入 PAUSED，不误标失败
                    if (failure.getErrorCode() == ErrorCode.AGENT_RUN_PAUSED && repository.pauseIfRequested(run)) {
                        return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
                    }
                    return failWriteProposal(run, vtc.toolCall(), failure.getErrorCode().name());
                } catch (RuntimeException failure) {
                    log.warn("Agent proposal failed: runId={}, tool={}, type={}, reason={}",
                            run.id(), vtc.toolCall().name(), failure.getClass().getSimpleName(),
                            failure.getMessage());
                    return failWriteProposal(run, vtc.toolCall(), "AGENT_TOOL_EXECUTION_FAILED");
                }

                // 记录工具结果
                ObjectNode toolResult = json.createObjectNode()
                        .put("status", "SUCCEEDED").put("effect", "PROPOSAL_PENDING")
                        .put("proposalId", outcome.approval().id().toString())
                        .put("revision", outcome.approval().revision());
                run = repository.recordToolResult(run, vtc.toolCall().name(), input(vtc.toolCall()),
                        toolResult, false);

                // 根据操作类型发出事件
                AgentEventType eventType = outcome.operation() == AgentProposalOutcome.Operation.CREATED
                        ? AgentEventType.APPROVAL_REQUESTED
                        : AgentEventType.APPROVAL_UPDATED;
                emit(run, eventType, json.createObjectNode()
                        .put("approvalId", outcome.approval().id().toString())
                        .put("toolName", vtc.toolCall().name()));

                // 确定性摘要：根据真实审批数据生成中文回答
                String summary = renderProposalSummary(outcome);
                List<AgentStepView> recorded = repository.listSteps(run.projectId(), run.id());
                if (validated.stream().anyMatch(v -> v.error() != null)
                        || (recorded != null && recorded.stream().anyMatch(s -> "TOOL_ERROR".equals(s.reason())))) {
                    summary += "\n部分调用未完成，请查看失败步骤；提案尚待审批。";
                }
                run = repository.recordFinal(run, summary, List.of());
                emit(run, AgentEventType.RUN_SUCCEEDED,
                        json.createObjectNode().put("status", AgentRunStatus.SUCCEEDED.name()));

                return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, summary, vtc.toolCall().name(), null);
            } else {
                // Independent reads were completed before proposal creation.
            }
        }

        // flush 剩余只读批次
        if (!readOnlyBatch.isEmpty()) {
            BatchOutcome outcome = executeToolBatch(run, readOnlyBatch, toolCtx, ctx.limits());
            if (outcome.paused()) return new AgentWorkerOutcome(AgentRunStatus.PAUSED, null, null, "RUN_PAUSED");
            run = outcome.run();
        }

        // 所有工具执行完成，重新排队等待下一轮
        cancellation.throwIfRequested(run);
        var recoveredProposal = recovered.stream().filter(result -> "PROPOSAL_PENDING".equals(result.path("effect").asText())).findFirst();
        if(recovered.stream().anyMatch(r->r.path("data").has("operationId"))) {
            String summary="已恢复对应规划操作。请查看规划卡片的真实状态，完成后人工审阅确认。";
            repository.recordFinal(run,summary,List.of());emit(run,AgentEventType.RUN_SUCCEEDED,json.createObjectNode().put("status","SUCCEEDED").put("recovered",true));
            return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED,summary,null,null);
        }
        if (recoveredProposal.isPresent()) {
            String summary = "已恢复提案，待审批。提案 ID：" + recoveredProposal.get().path("proposalId").asText()
                    + "，修订 #" + recoveredProposal.get().path("revision").asInt();
            repository.recordFinal(run, summary, List.of());
            emit(run, AgentEventType.RUN_SUCCEEDED, json.createObjectNode().put("status", "SUCCEEDED").put("recovered", true));
            return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, summary, null, null);
        }
        repository.requeueRun(run);
        return new AgentWorkerOutcome(AgentRunStatus.QUEUED, null, null, null);
    }

    private AgentWorkerOutcome failWriteProposal(
            AgentRunView run, ModelToolCall toolCall, String errorCode) {
        ObjectNode result = createError(errorCode, "提案提交未完成");
        result.put("status", "AGENT_TOOL_EXECUTION_FAILED".equals(errorCode) ? "UNKNOWN" : "REJECTED");
        run = repository.recordToolResult(run, toolCall.name(), input(toolCall), result, true);
        emit(run, AgentEventType.TOOL_CALL_FAILED,
                json.createObjectNode()
                        .put("callId", toolCall.id())
                        .put("toolName", toolCall.name())
                        .put("errorCode", errorCode));
        repository.recordFailure(run, errorCode, false);
        emit(run, AgentEventType.RUN_FAILED,
                json.createObjectNode()
                        .put("errorCode", errorCode)
                        .put("retryable", false));
        return new AgentWorkerOutcome(AgentRunStatus.FAILED, null, toolCall.name(), errorCode);
    }

    /**
     * 验证单个工具调用（纯校验，无 I/O）。
     * 返回 ValidatedToolCall，包含解析后的工具、错误信息和是否致命。
     */
    private ValidatedToolCall validateToolCall(
            AgentRunView run, ModelToolCall toolCall,
            AgentExecutionContext ctx, AgentSkill skill,
            List<AgentToolDefinition> exposed, List<AgentStepView> steps,
            AgentToolContext toolCtx, boolean recoveryBatch, boolean requestLegacyMode) {

        // 工具存在检查
        AgentTool tool = tools.find(toolCall.name(), ctx).orElse(null);
        if (tool == null) {
            return new ValidatedToolCall(null, toolCall, createError("TOOL_NOT_FOUND", "工具未注册"), false);
        }

        // Skill 工具白名单检查
        boolean allowedMcp = toolCall.name().startsWith("mcp.") && skill.allowExternalTools();
        if (!skill.allowedTools().contains(toolCall.name()) && !allowedMcp
                && !com.shitulelv.aicollab.agent.infrastructure.tool.RequestUserInputAgentTool.NAME.equals(toolCall.name())) {
            return new ValidatedToolCall(null, toolCall, createError("TOOL_NOT_ALLOWED", "当前 Skill 不允许使用此工具"), false);
        }

        // 角色权限检查
        try {
            tools.checkPolicy(tool, toolCtx);
        } catch (IllegalArgumentException e) {
            return new ValidatedToolCall(null, toolCall, createError("TOOL_NOT_ALLOWED", "工具权限不足"), false);
        }

        // Schema 校验
        AgentToolDefinition toolDef = exposed.stream()
                .filter(d -> d.name().equals(toolCall.name()))
                .findFirst().orElse(null);
        if (toolDef == null) {
            return new ValidatedToolCall(null, toolCall, createError("TOOL_NOT_ALLOWED", "工具未在本轮暴露"), false);
        }
        if (toolDef != null) {
            String validationError = ToolArgumentValidator.validate(
                    toolCall.arguments(), toolDef.inputSchema());
            if (validationError != null) {
                return new ValidatedToolCall(null, toolCall, createError("TOOL_ARGUMENT_INVALID", validationError), false);
            }
        }

        if (!tool.writesBusinessData()) {
            var previous=steps.stream().filter(step -> step.type()==AgentStepType.TOOL_CALL_COMPLETED && toolCall.name().equals(step.toolName()))
                    .filter(step -> step.input()!=null && toolCall.arguments().equals(step.input().has("arguments") ? step.input().path("arguments") : step.input()))
                    .filter(step -> step.output()!=null && "FAILED".equals(step.output().path("status").asText())).reduce((first,last) -> last);
            if (previous.isPresent()) {
                JsonNode result=previous.get().output();
                boolean retryable=result.path("retryable").asBoolean() || result.path("error").path("retryable").asBoolean();
                if (!retryable || !repository.consumeRecovery(run,"TOOL_RETRY",1))
                    return new ValidatedToolCall(null,toolCall,createError("TOOL_RETRY_EXHAUSTED","该失败不可重试或已用完工具重试预算"),false);
            }
        }
        // 循环检测
        AgentDecision.CallTool nextCall = toCallTool(toolCall);
        if (loopGuard.hasNoProgress(steps, nextCall)) {
            return new ValidatedToolCall(null, toolCall, createError("AGENT_NO_PROGRESS", "工具调用无进展"), true);
        }

        // 写工具：Skill 级别开关 + Legacy 校验。校验模式按调用来源确定：
        // 恢复批次用原模型轮次持久化的 source_mode（换模型不追溯否定原生轮次的提案，
        // 也不放行 Legacy 轮次的越权写调用）；新轮次用本次请求解析出的模式。
        // 权限、审批与 Skill 白名单对所有来源继续生效。
        if(tool.writesBusinessData()) {
            if (!skill.allowWriteTools()) {
                return new ValidatedToolCall(null, toolCall, createError("SKILL_WRITE_FORBIDDEN", "当前 Skill 不允许写操作"), false);
            }
            boolean legacyForCall;
            if (recoveryBatch) {
                String sourceMode = repository.invocationSourceMode(run, toolCall);
                legacyForCall = sourceMode != null ? "LEGACY_READ_ONLY".equals(sourceMode)
                        : modelExecutor.isLegacyModeForRun(run);
            } else {
                legacyForCall = requestLegacyMode;
            }
            if (legacyForCall) {
                return new ValidatedToolCall(null, toolCall, createError("LEGACY_WRITE_TOOL_FORBIDDEN", "Legacy 模式下禁止执行写工具"), true);
            }
        }
        if (tool instanceof ApprovalWriteAgentTool writeTool) {
            return new ValidatedToolCall(writeTool, toolCall, null, false);
        }

        return new ValidatedToolCall(tool, toolCall, null, false);
    }

    /** 批次执行结果：run 为最新运行视图；paused 表示有调用因暂停意图未获准入而保持 PENDING。 */
    private record BatchOutcome(AgentRunView run, boolean paused) {
    }

    /**
     * 并行执行一批只读工具，顺序记录结果。
     * 每个工具执行都有超时保护，防止永久挂起。
     *
     * <p>暂停控制：每个任务在线程池/限流等待之后、实际调用之前复核暂停意图与 claim——
     * 已获准入（通过复核）的调用允许完成并落结果；未获准入的调用以哨兵返回，
     * 保持 invocation PENDING、不误标 SKIPPED/失败，随后运行转入 PAUSED。</p>
     */
    private BatchOutcome executeToolBatch(
            AgentRunView run,
            List<ValidatedToolCall> batch,
            AgentToolContext toolCtx, AgentRuntimeLimits limits) {

        // 并行执行所有只读工具
        List<java.util.concurrent.Future<ToolExecutionResult>> futures = new ArrayList<>();
        List<Long> deadlines = new ArrayList<>();
        for (ValidatedToolCall vtc : batch) {
            AgentTool tool = vtc.tool();
            ModelToolCall toolCall = vtc.toolCall();
            deadlines.add(System.nanoTime() + (toolCall.name().startsWith("mcp.")
                    ? limits.mcpToolTimeout() : limits.internalToolTimeout()).toNanos());
            cancellation.throwIfRequested(run);
            AgentRunView executingRun = run;
            try {
            futures.add(scheduler.executor().submit(() -> {
                try (var permit = scheduler.acquire(toolCtx)) {
                    cancellation.throwIfRequested(executingRun);
                    // 限流等待之后的准入复核：暂停意图先落库则本调用不启动
                    if (repository.isPauseRequested(executingRun.projectId(), executingRun.id())) {
                        return new ToolExecutionResult(toolCall, null, PAUSE_ADMISSION);
                    }
                    tools.checkPolicy(tool, toolCtx);
                    emit(executingRun, AgentEventType.TOOL_CALL_STARTED, json.createObjectNode()
                            .put("callId", toolCall.id()).put("toolName", toolCall.name())
                            .put("invocationId",java.util.Objects.toString(repository.invocationId(executingRun,toolCall),"")));
                    AgentToolResult result = tool.execute(toolCtx.withInvocationId(repository.invocationId(executingRun,toolCall)), toolCall.arguments());
                    return new ToolExecutionResult(toolCall, result, null);
                } catch (BusinessException e) {
                    return new ToolExecutionResult(toolCall, null, e);
                } catch (RuntimeException e) {
                    return new ToolExecutionResult(toolCall, null, e);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    return new ToolExecutionResult(toolCall, null, new BusinessException(ErrorCode.AGENT_RUN_CANCELED));
                }
            }));
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                futures.add(CompletableFuture.completedFuture(new ToolExecutionResult(toolCall,
                        AgentToolResult.failed("TOOL_EXECUTOR_BUSY", "工具队列已满", true), null)));
            }
        }

        // 顺序记录结果（保证 run 状态一致性），带超时保护
        boolean pauseRequested = false;
        try {
        for (java.util.concurrent.Future<ToolExecutionResult> future : futures) {
            ToolExecutionResult er;
            try {
                // 根据工具类型选择超时时间：MCP 工具 15s，内部工具 10s
                long remaining = deadlines.get(futures.indexOf(future)) - System.nanoTime();
                er = future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 超时：取消任务并记录超时错误
                future.cancel(true);
                ModelToolCall timedOutCall = futures.indexOf(future) >= 0 ?
                        batch.get(futures.indexOf(future)).toolCall() : null;
                if (timedOutCall != null) {
                    run = recordToolTimeout(run, timedOutCall);
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
            } catch (java.util.concurrent.ExecutionException e) {
                // 执行异常：提取内部异常
                Throwable cause = e.getCause();
                ModelToolCall failedCall = batch.get(futures.indexOf(future)).toolCall();
                if (cause instanceof BusinessException be) {
                    er = new ToolExecutionResult(failedCall, null, be);
                } else if (cause instanceof RuntimeException re) {
                    er = new ToolExecutionResult(failedCall, null, re);
                } else {
                    er = new ToolExecutionResult(failedCall, null, new RuntimeException(cause));
                }
            }

            if (er.exception() instanceof BusinessException be) {
                if (be.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) {
                    throw be;
                }
                if (be.getErrorCode() == ErrorCode.AGENT_RUN_PAUSED) {
                    // 未获准入的调用：保持 PENDING，不记录结果、不占额度
                    pauseRequested = true;
                    continue;
                }
                run = recordToolFailure(run, er.toolCall(), be);
            } else if (er.exception() instanceof RuntimeException re) {
                run = recordToolFailure(run, er.toolCall(), re);
            } else {
                cancellation.throwIfRequested(run);
                JsonNode resultJson = json.valueToTree(er.result());
                JsonNode sanitizedResult = sanitizer.sanitize(resultJson);

                ObjectNode inputWithId = json.createObjectNode();
                inputWithId.put("toolCallId", er.toolCall().id());
                inputWithId.set("arguments", er.toolCall().arguments());

                boolean failed = er.result().status() != AgentToolResult.Status.SUCCEEDED;
                run = repository.recordToolResult(run, er.toolCall().name(), inputWithId, sanitizedResult, failed);
                emit(run, failed ? AgentEventType.TOOL_CALL_FAILED : AgentEventType.TOOL_CALL_COMPLETED,
                        json.createObjectNode()
                                .put("callId", er.toolCall().id())
                                .put("toolName", er.toolCall().name())
                                .put("status", er.result().status().name())
                                .put("errorCode", failed && er.result().error() != null ? er.result().error().code() : "")
                                .put("summary", er.result().data() == null ? "工具执行完成" : "工具执行完成"));
            }
        }
        } finally { futures.forEach(f -> { if (!f.isDone()) f.cancel(true); }); }

        if (pauseRequested) {
            // 结清已准入调用的结果后确认暂停：未启动调用保持 PENDING，恢复后按原身份继续
            return new BatchOutcome(repository.recordPaused(run.projectId(), run.id()), true);
        }
        return new BatchOutcome(run, false);
    }

    /**
     * 记录工具执行超时。
     */
    private AgentRunView recordToolTimeout(AgentRunView run, ModelToolCall toolCall) {
        ObjectNode errorResult = json.createObjectNode();
        errorResult.put("status", "FAILED");
        errorResult.put("retryable", true);
        errorResult.put("error", "TOOL_EXECUTION_TIMEOUT");
        errorResult.put("message", "工具执行超时");
        JsonNode sanitizedError = sanitizer.sanitize(errorResult);

        ObjectNode inputWithId = json.createObjectNode();
        inputWithId.put("toolCallId", toolCall.id());
        inputWithId.set("arguments", toolCall.arguments());

        run = repository.recordToolResult(run, toolCall.name(), inputWithId, sanitizedError, true);
        emit(run, AgentEventType.TOOL_CALL_FAILED,
                json.createObjectNode()
                        .put("callId", toolCall.id())
                        .put("toolName", toolCall.name())
                        .put("errorCode", "TOOL_EXECUTION_TIMEOUT")
                        .put("retryable", true));
        return run;
    }

    private record ValidatedToolCall(
            AgentTool tool, ModelToolCall toolCall,
            ObjectNode error, boolean fatal) {
        /** 写工具时返回 ApprovalWriteAgentTool，只读工具时返回 null */
        ApprovalWriteAgentTool writeTool() {
            if (tool instanceof ApprovalWriteAgentTool aw) return aw;
            return null;
        }
    }

    private record ToolExecutionResult(
            ModelToolCall toolCall, AgentToolResult result, Exception exception) {
    }

    private ObjectNode createError(String code, String message) {
        ObjectNode error = json.createObjectNode();
        error.put("error", code);
        error.put("message", message);
        error.put("status", "REJECTED");
        error.put("retryable", false);
        return (ObjectNode) sanitizer.sanitize(error);
    }

    private AgentRunView recordToolFailure(
            AgentRunView run, ModelToolCall toolCall, RuntimeException failure) {
        ObjectNode errorResult = json.createObjectNode();
        String code = failure instanceof BusinessException business ? business.getErrorCode().name() : "TOOL_EXECUTION_FAILED";
        errorResult.put("error", code);
        errorResult.put("status", "FAILED");
        errorResult.put("retryable", "AI_MODEL_TIMEOUT".equals(code) || "AI_PROVIDER_ERROR".equals(code));
        // 业务异常的 message 由代码作者编写的确定性中文原因（如"规划日期超出项目范围"），
        // 是模型纠正参数所需的最小信息；非业务异常保持通用提示，不透出堆栈或 SQL。
        errorResult.put("message",
                failure instanceof BusinessException business && business.getMessage() != null
                        && !business.getMessage().isBlank()
                        ? business.getMessage()
                        : "工具执行失败");
        JsonNode sanitizedError = sanitizer.sanitize(errorResult);

        // 保存原始 toolCallId 到 input_json
        ObjectNode inputWithId = json.createObjectNode();
        inputWithId.put("toolCallId", toolCall.id());
        inputWithId.set("arguments", toolCall.arguments());

        run = repository.recordToolResult(run, toolCall.name(), inputWithId, sanitizedError, true);
        emit(run, AgentEventType.TOOL_CALL_FAILED,
                json.createObjectNode()
                        .put("callId", toolCall.id())
                        .put("toolName", toolCall.name())
                        .put("errorCode", code)
                        .put("retryable", false));
        return run;
    }

    private static AgentDecision.CallTool toCallTool(ModelToolCall toolCall) {
        return new AgentDecision.CallTool(toolCall.name(), toolCall.arguments(), "");
    }

    private ObjectNode input(ModelToolCall call) {
        ObjectNode input = json.createObjectNode().put("toolCallId", call.id());
        input.set("arguments", call.arguments());
        return input;
    }

    /**
     * 渲染提案摘要：根据真实审批数据生成确定性中文回答。
     */
    private String renderProposalSummary(AgentProposalOutcome outcome) {
        String operation = outcome.operation() == AgentProposalOutcome.Operation.CREATED
                ? "已生成" : "已更新";
        String familyName = extractFamilyName(outcome.approval().proposalFamily());
        String title = extractTitle(outcome.currentArguments());
        int revision = outcome.approval().revision();

        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(familyName).append("提案");
        if (title != null) {
            sb.append("：").append(title);
        }
        if (outcome.operation() == AgentProposalOutcome.Operation.UPDATED) {
            sb.append("（修订 #").append(revision).append("）");
        }
        sb.append("。");
        appendProposalField(sb, outcome.currentArguments(), "assigneeId", "负责人");
        appendProposalField(sb, outcome.currentArguments(), "priority", "优先级");
        appendProposalField(sb, outcome.currentArguments(), "dueDate", "截止日期");
        appendProposalField(sb, outcome.currentArguments(), "milestoneId", "里程碑");
        sb.append("\n当前审批状态：待审批。审批 ID：").append(outcome.approval().id());
        return sb.toString();
    }

    private void appendProposalField(
            StringBuilder summary, JsonNode arguments, String field, String label) {
        if (arguments == null || !arguments.hasNonNull(field)) return;
        String value = arguments.path(field).asText();
        if (!value.isBlank()) summary.append("\n- ").append(label).append("：").append(value);
    }

    private String extractFamilyName(AgentProposalFamily family) {
        if (family == null) return "写入";
        return switch (family) {
            case TASK_CREATE -> "任务创建";
            case TASK_UPDATE -> "任务更新";
            case MILESTONE_CREATE -> "里程碑创建";
            case MILESTONE_UPDATE -> "里程碑更新";
            case MEMORY_CREATE -> "项目记忆";
        };
    }

    private String extractTitle(JsonNode arguments) {
        if (arguments == null) return null;
        JsonNode titleNode = arguments.get("title");
        if (titleNode != null && !titleNode.isNull()) {
            return titleNode.asText();
        }
        return null;
    }

    private void emit(AgentRunView run, AgentEventType type, JsonNode payload) {
        if (repository.atomicEventsEnabled() && com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder.ownsEvent(type)) return;
        if (events != null) events.append(run.projectId(), run.id(), type, payload);
    }
}
