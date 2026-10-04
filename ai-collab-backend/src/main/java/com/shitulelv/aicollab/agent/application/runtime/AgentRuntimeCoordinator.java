package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.domain.policy.AgentConvergencePolicy;
import com.shitulelv.aicollab.agent.domain.policy.AgentLoopGuard;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runtime Coordinator。负责一次 Run 的状态推进，协调 Context、Plan、Skill、Model 和 Tool。
 * - 不直接访问 Mapper
 * - 不拼接 Provider 请求
 * - 不包含具体业务工具逻辑
 * - 通过 RoutingAgentModelExecutor 选择正确的模型执行路径
 *
 * <p>消息组装与工具批次执行分别由 {@link AgentModelMessageComposer}
 * 和 {@link AgentToolCallExecutor} 承担；本类只保留编排与收尾路径。</p>
 */
@Service
public class AgentRuntimeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeCoordinator.class);

    private final AgentRepository repository;
    private final AgentContextAssembler contextAssembler;
    private final AgentSkillRegistry skillRegistry;
    private final AgentPlanService planService;
    private final AgentToolRegistry tools;
    private final AgentCancellationService cancellation;
    private final AgentConvergencePolicy convergencePolicy;
    private final ObjectMapper json;
    private final AgentEventService events;
    private final RoutingAgentModelExecutor modelExecutor;
    private final AgentModelMessageComposer composer;
    private final AgentToolCallExecutor toolExecutor;
    private final AgentContextSummarizer summarizer;
    private final AgentContextProperties contextProperties;

    @Autowired
    public AgentRuntimeCoordinator(
            AgentRepository repository,
            AgentContextAssembler contextAssembler,
            AgentSkillRegistry skillRegistry,
            AgentPlanService planService,
            AgentToolRegistry tools,
            AgentCancellationService cancellation,
            AgentLoopGuard loopGuard,
            AgentApprovalService approvals,
            RoutingAgentModelExecutor modelExecutor,
            AgentToolResultSanitizer sanitizer,
            AgentConvergencePolicy convergencePolicy,
            ObjectMapper json,
            AgentEventService events,
            AgentMemoryService memories,
            AgentContextProperties contextProperties) {
        this.repository = repository;
        this.contextAssembler = contextAssembler;
        this.skillRegistry = skillRegistry;
        this.planService = planService;
        this.tools = tools;
        this.cancellation = cancellation;
        this.modelExecutor = modelExecutor;
        this.convergencePolicy = convergencePolicy;
        this.json = json;
        this.events = events;
        this.contextProperties = contextProperties == null ? AgentContextProperties.defaults() : contextProperties;
        this.composer = new AgentModelMessageComposer(repository, memories, json, modelExecutor);
        this.toolExecutor = new AgentToolCallExecutor(repository, tools, cancellation, loopGuard,
                approvals, modelExecutor, sanitizer, json, events);
        this.summarizer = new AgentContextSummarizer(repository, modelExecutor, json);
    }

    public AgentRuntimeCoordinator(
            AgentRepository repository,
            AgentContextAssembler contextAssembler,
            AgentSkillRegistry skillRegistry,
            AgentPlanService planService,
            AgentToolRegistry tools,
            AgentCancellationService cancellation,
            AgentLoopGuard loopGuard,
            AgentApprovalService approvals,
            RoutingAgentModelExecutor modelExecutor,
            AgentToolResultSanitizer sanitizer,
            ObjectMapper json) {
        this(repository, contextAssembler, skillRegistry, planService, tools, cancellation,
                loopGuard, approvals, modelExecutor, sanitizer, new AgentConvergencePolicy(),
                json, null, null, AgentContextProperties.defaults());
    }

    /** 兼容既有装配与测试：使用默认上下文预算配置。 */
    public AgentRuntimeCoordinator(
            AgentRepository repository,
            AgentContextAssembler contextAssembler,
            AgentSkillRegistry skillRegistry,
            AgentPlanService planService,
            AgentToolRegistry tools,
            AgentCancellationService cancellation,
            AgentLoopGuard loopGuard,
            AgentApprovalService approvals,
            RoutingAgentModelExecutor modelExecutor,
            AgentToolResultSanitizer sanitizer,
            AgentConvergencePolicy convergencePolicy,
            ObjectMapper json,
            AgentEventService events,
            AgentMemoryService memories) {
        this(repository, contextAssembler, skillRegistry, planService, tools, cancellation,
                loopGuard, approvals, modelExecutor, sanitizer, convergencePolicy,
                json, events, memories, AgentContextProperties.defaults());
    }

    /**
     * 判断当前是否为 Legacy 模式（只支持 CHAT，不支持 NATIVE_TOOLS）。
     */
    public boolean isLegacyMode(UUID callerUserId) {
        return modelExecutor.isLegacyMode(callerUserId);
    }

    @Autowired
    void configureToolScheduler(AgentToolScheduler scheduler) {
        toolExecutor.scheduler = scheduler.executor();
        toolExecutor.limiter = scheduler;
    }

    /**
     * 推进一次 Run。
     */
    public AgentWorkerOutcome advance(AgentRunView run) {
        try (var accounting=new AgentModelAccounting();var deadline=new com.shitulelv.aicollab.infrastructure.ai.model.AiRequestDeadline()) {
            // 1. 取消检查
            cancellation.throwIfRequested(run);

            // 2. 组装可信上下文
            AgentExecutionContext ctx = contextAssembler.assemble(
                    run, run.skillCode(), composer.parsePageContext(run.pageContextJson()));
            long remainingMillis=Math.min(300000,ctx.limits().maxRunDuration().toMillis())-repository.activeElapsedMillis(run);
            if (remainingMillis<=0) return budgetExceeded(run);
            deadline.limit(java.time.Duration.ofMillis(remainingMillis));
            emitOnce(run, AgentEventType.CONTEXT_CAPTURED,
                    json.createObjectNode().put("route", ctx.page().route()));

            // 3. 选择 Skill
            AgentSkill skill = skillRegistry.select(run.skillCode(), run.goal(), ctx.page());
            emitOnce(run, AgentEventType.SKILL_SELECTED,
                    json.createObjectNode().put("skillCode", skill.code()));

            // 4. 确保计划（更新 version）
            AgentPlan plan = planService.ensurePlan(run, skill);
            emitOnce(run, AgentEventType.PLAN_CREATED, json.valueToTree(plan));
            // 刷新 Run 以获取最新 version
            run = repository.findRun(run.projectId(), run.id()).orElse(run);

            // 5. 获取当前步骤列表用于循环检测
            List<AgentStepView> steps = repository.listSteps(run.projectId(), run.id());
            var unfinished = repository.pendingModelTurn(run);
            if (unfinished != null && unfinished.isPresent())
                return toolExecutor.executeCalls(run,ctx,skill,unfinished.get(),tools.definitionsFor(ctx,skill),steps);

            AgentConvergencePolicy.Decision convergence =
                    convergencePolicy.decide(run, ctx.limits(), steps);
            if (convergence.mode() == AgentConvergencePolicy.Mode.EXHAUSTED) {
                AgentWorkerOutcome fallback = completeFromEvidence(run, steps);
                if (fallback != null) return fallback;
                return budgetExceeded(run);
            }
            boolean finalizing = convergence.mode() == AgentConvergencePolicy.Mode.FINALIZE;
            boolean coreActionPending = isCoreActionPending(run, skill);

            // 6. 获取允许的工具定义
            List<AgentToolDefinition> exposed = tools.definitionsFor(ctx, skill);
            if (finalizing) {
                exposed = List.of();
            }

            // 7. 单次请求输入预算：min(模型窗口-输出预留-安全余量, 运行剩余输入预算, 应用单次上限)
            int remainingRunInput = Math.min(run.maxInputTokens(), ctx.limits().maxInputTokens()) - run.inputTokensUsed();
            var providerIdentity = modelExecutor.pinnedProviderIdentity(run);
            var modelWindow = AgentContextBudget.resolveWindow(
                    providerIdentity == null ? null : providerIdentity.providerType(),
                    providerIdentity == null ? null : providerIdentity.modelName(),
                    contextProperties.windowOverrides());
            var requestBudget = AgentContextBudget.perRequest(contextProperties, modelWindow, remainingRunInput);

            List<ModelMessage> messages;
            AgentModelMessageComposer.Composition composition = null;
            int estimatedInput;
            String overBudgetReason = null;
            if (contextProperties.composerV2()) {
                composition = composer.composeV2(run, ctx, skill, plan, steps, requestBudget.availableInputTokens(), 1.0);
                if (composition.failureReason() != null) {
                    // 必选层（含当前请求）无法完整放入预算：明确停止，不静默截断
                    return inputBudgetExceeded(run, requestBudget, composition.failureReason());
                }
                messages = appendTurnInstructions(composition.messages(), steps, finalizing, ctx.limits().maxToolCallsPerTurn(), coreActionPending);
                estimatedInput = estimateInput(messages, exposed);
                if (estimatedInput > requestBudget.availableInputTokens()) {
                    // 降级重组一次：收紧预算并重试，仍超限才明确停止
                    composition = composer.composeV2(run, ctx, skill, plan, steps, requestBudget.availableInputTokens(), 0.6);
                    messages = appendTurnInstructions(composition.messages(), steps, finalizing, ctx.limits().maxToolCallsPerTurn(), coreActionPending);
                    estimatedInput = estimateInput(messages, exposed);
                    if (estimatedInput > requestBudget.availableInputTokens()) {
                        overBudgetReason = "COMPOSITION_OVER_BUDGET";
                    } else {
                        log.debug("上下文预算降级重组生效: run={}, estimated={}, available={}",
                                run.id(), estimatedInput, requestBudget.availableInputTokens());
                    }
                }
            } else {
                messages = appendTurnInstructions(composer.buildMessageHistory(run, ctx, skill, plan, steps), steps, finalizing, ctx.limits().maxToolCallsPerTurn(), coreActionPending);
                estimatedInput = estimateInput(messages, exposed);
                if (estimatedInput > requestBudget.availableInputTokens()) {
                    overBudgetReason = "COMPOSITION_OVER_BUDGET";
                }
            }
            if (overBudgetReason != null) {
                return inputBudgetExceeded(run, requestBudget, overBudgetReason);
            }
            if (!finalizing && convergence.successfulToolCalls() > 0
                    && convergencePolicy.needsFinalRequest(run, ctx.limits(), steps, estimatedInput, contextProperties.outputReserveTokens())) {
                finalizing = true;
                exposed = List.of();
                messages = appendTurnInstructions(composition != null ? composition.messages()
                        : composer.buildMessageHistory(run, ctx, skill, plan, steps), steps, true, ctx.limits().maxToolCallsPerTurn(), coreActionPending);
                estimatedInput = estimateInput(messages, exposed);
                if (estimatedInput > requestBudget.availableInputTokens())
                    return inputBudgetExceeded(run, requestBudget, "FINAL_REQUEST_OVER_BUDGET");
            }
            AgentModelAccounting.estimate(estimatedInput);

            // 7b. 有界增量摘要：主请求预算保留后，对未覆盖旧对话生成一次摘要（持久化尝试标记、CAS 提交、单独记账）
            if (composition != null) {
                int finalInputReserve = finalizing ? 0 : Math.max(estimatedInput, steps.stream()
                        .filter(s -> s.type() == AgentStepType.MODEL_TURN && s.promptTokens() != null)
                        .reduce((a, b) -> b).map(AgentStepView::promptTokens).orElse(0));
                long runDurationBudget = Math.min(300000, ctx.limits().maxRunDuration().toMillis());
                AgentRunView runForSummary = run;
                summarizer.maybeSummarize(run, composition,
                        Math.max(0, remainingRunInput - estimatedInput - finalInputReserve),
                        () -> runDurationBudget - repository.activeElapsedMillis(runForSummary) > 0);
                // 摘要消耗已入账：刷新运行、重查取消状态，并重新核算主请求预算——
                // 不允许携带超限上下文继续请求模型
                run = repository.findRun(run.projectId(), run.id()).orElse(run);
                cancellation.throwIfRequested(run);
                int refreshedRemaining = Math.min(run.maxInputTokens(), ctx.limits().maxInputTokens()) - run.inputTokensUsed();
                var refreshedBudget = AgentContextBudget.perRequest(contextProperties, modelWindow, refreshedRemaining);
                if (estimatedInput > refreshedBudget.availableInputTokens()) {
                    return inputBudgetExceeded(run, refreshedBudget, "SUMMARY_CONSUMED_BUDGET");
                }
            }

            // 8. 调用模型（通过路由选择正确的执行器）
            var pendingTurn = repository.pendingModelTurn(run);
            if (pendingTurn != null && pendingTurn.isPresent()) {
                return toolExecutor.executeCalls(run, ctx, skill, pendingTurn.get(), tools.definitionsFor(ctx, skill), steps);
            }
            ModelTurnResult turn;
            try {
                var startedPayload = json.createObjectNode().put("modelTurn", run.stepsUsed() + 1);
                if (composition != null && composition.stats() != null) {
                    // 单次请求体积分解：让"上下文为什么如此大"有实测依据（系统提示/状态/摘要/
                    // 页面/提案/工具观察/历史/记忆分列；工具定义与估算 token 由本层补充）
                    var breakdown = startedPayload.putObject("inputBreakdown");
                    composition.stats().layerChars().forEach(breakdown::put);
                    breakdown.put("toolDefinitionsChars", json.valueToTree(exposed).toString().length());
                    breakdown.put("estimatedInputTokens", estimatedInput);
                }
                emit(run, AgentEventType.MODEL_STARTED, startedPayload);
                turn = modelExecutor.callModel(run, messages, exposed, run.correctionAttempted());
                String orphanCallId = modelCallId(run, messages);
                boolean cancelSettled = false;
                if (repository.isCancelRequested(run.projectId(), run.id())) {
                    // 模型调用已发生：先按调用身份幂等结算这次消耗，再进入取消终态，用量不因取消丢失
                    repository.settleOrphanUsage(run.projectId(), run.id(), orphanCallId, "MODEL_TURN",
                            turnSettlement(turn));
                    cancelSettled = true;
                }
                try {
                    cancellation.throwIfRequested(run);
                } catch (BusinessException canceled) {
                    if (!cancelSettled) {
                        // 竞争窗口：两次取消检查之间收到取消——同一调用身份只入账一次
                        repository.settleOrphanUsage(run.projectId(), run.id(), orphanCallId, "MODEL_TURN",
                                turnSettlement(turn));
                    }
                    throw canceled;
                }
            } catch (IllegalArgumentException malformed) {
                boolean repair=repository.consumeRecovery(run,"FORMAT_REPAIR",1);
                if (repair) {
                    repository.recordFailure(run,"FORMAT_REPAIR_REQUESTED",true);
                    return new AgentWorkerOutcome(AgentRunStatus.FAILED_RETRYABLE,null,null,"FORMAT_REPAIR_REQUESTED");
                }
                repository.recordFailure(run,"FORMAT_REPAIR_EXHAUSTED",false);
                return new AgentWorkerOutcome(AgentRunStatus.FAILED,null,null,"FORMAT_REPAIR_EXHAUSTED");
            } catch (BusinessException failure) {
                Thread.interrupted();
                cancellation.throwIfRequested(run);
                if (failure.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) {
                    throw failure;
                }
                if (failure.getErrorCode()==ErrorCode.AI_PROVIDER_INVALID_RESPONSE && repository.consumeRecovery(run,"FORMAT_REPAIR",1)) {
                    repository.recordFailure(run,"FORMAT_REPAIR_REQUESTED",true);
                    return new AgentWorkerOutcome(AgentRunStatus.FAILED_RETRYABLE,null,null,"FORMAT_REPAIR_REQUESTED");
                }
                boolean retryable = failure.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT
                        || failure.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR;
                repository.recordFailure(run, failure.getErrorCode().name(), retryable);
                AgentRunView failedRun = repository.findRun(run.projectId(), run.id()).orElse(run);
                emit(run, AgentEventType.RUN_FAILED,
                        json.createObjectNode()
                                .put("errorCode", failure.getErrorCode().name())
                                .put("retryable", retryable));
                return new AgentWorkerOutcome(
                        failedRun == run ? (retryable ? AgentRunStatus.FAILED_RETRYABLE : AgentRunStatus.FAILED) : failedRun.status(),
                        null, null, failure.getErrorCode().name());
            }

            int outputTokens=turn.usage()!=null && turn.usage().outputTokens()!=null ? turn.usage().outputTokens() : Math.max(1,(json.valueToTree(turn).toString().length()+2)/3);
            // 输出超额按真实累计值（actual）判定：used 被封顶会低估消耗，实际可能已超
            long outputRemaining=Math.min(run.maxOutputTokens(),ctx.limits().maxOutputTokens())-(long)run.outputTokensActual();
            if (outputTokens>outputRemaining) {
                repository.recordBudgetExceeded(run,new com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult(turn.content(),turn.provider(),turn.model(),
                        turn.usage()==null ? null : turn.usage().inputTokens(),turn.usage()==null ? null : turn.usage().outputTokens(),turn.latencyMs()));
                return new AgentWorkerOutcome(AgentRunStatus.BUDGET_EXCEEDED,null,null,"AGENT_BUDGET_EXCEEDED");
            }
            // 9. 记录 assistant turn（返回更新后的 Run）
            run = repository.recordModelTurn(run, turn);
            // 输入实际超额：真实输入消耗超出运行上限时结算真实值并明确终止，
            // 不执行该响应中的工具，不记成功（估算无法绝对保证请求不超限，如实结算+停止）
            long inputRemaining=Math.min(run.maxInputTokens(),ctx.limits().maxInputTokens())-run.inputTokensActual();
            if (inputRemaining<0) {
                repository.recordBudgetExceeded(run);
                emit(run, AgentEventType.RUN_BUDGET_EXCEEDED,
                        json.createObjectNode()
                                .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                                .put("errorCode", "AGENT_BUDGET_EXCEEDED")
                                .put("scope", "RUN_INPUT_ACTUAL_OVERSET"));
                return new AgentWorkerOutcome(AgentRunStatus.BUDGET_EXCEEDED,null,null,"AGENT_BUDGET_EXCEEDED");
            }
            emit(run, AgentEventType.MODEL_COMPLETED,
                    json.createObjectNode()
                            .put("finishReason", turn.finishReason().name())
                            .put("toolCallCount", turn.toolCalls().size()));

            if (finalizing && (!turn.toolCalls().isEmpty() || turn.content().isBlank())) {
                AgentWorkerOutcome fallback = completeFromEvidence(run, steps);
                if (fallback != null) return fallback;
                return invalidResponse(run);
            }

            // 10. 如果有工具调用，执行
            if (!turn.toolCalls().isEmpty()) {
                try {
                    convergencePolicy.validateToolBatch(run, ctx.limits(), turn.toolCalls().size());
                } catch (IllegalArgumentException overBudget) {
                    AgentWorkerOutcome fallback = completeFromEvidence(run, steps);
                    if (fallback != null) return fallback;
                    return budgetExceeded(run);
                }
                return toolExecutor.executeCalls(run, ctx, skill, turn, exposed, steps);
            }

            // 11. 如果有最终文本，检查是否需要用户输入
            if (!turn.content().isBlank()) {
                // 检测 [QUESTIONS] 标记：模型需要用户澄清
                if (turn.content().startsWith("[QUESTIONS]")) {
                    run = repository.recordWaitingForInput(run, turn.content());
                    emit(run, AgentEventType.WAITING_FOR_USER_INPUT,
                            json.createObjectNode().put("question", turn.content()));
                    return new AgentWorkerOutcome(AgentRunStatus.WAITING_FOR_USER_INPUT, turn.content(), null, null);
                }
                if (finalizing && coreActionPending) {
                    // 预算策略强制收尾且核心动作未发生：如实进入预算受限/部分完成状态，
                    // 不能仅凭模型返回文字记成功（"运行结束"≠"规划已生成"）
                    repository.recordBudgetPartialAnswer(run, turn.content());
                    emit(run, AgentEventType.RUN_BUDGET_EXCEEDED,
                            json.createObjectNode()
                                    .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                                    .put("errorCode", "AGENT_BUDGET_EXCEEDED")
                                    .put("scope", "CORE_ACTION_NOT_PERFORMED"));
                    return new AgentWorkerOutcome(AgentRunStatus.BUDGET_EXCEEDED, turn.content(), null, "AGENT_BUDGET_EXCEEDED");
                }
                // 正常完成
                run = repository.recordFinal(run, turn.content(), List.of());
                emit(run, AgentEventType.RUN_SUCCEEDED,
                        json.createObjectNode().put("status", AgentRunStatus.SUCCEEDED.name()));
                return new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, turn.content(), null, null);
            }

            // 12. 无效响应
            return invalidResponse(run);

        } catch (BusinessException e) {
            Thread.interrupted();
            if (e.getErrorCode() == ErrorCode.AGENT_RUN_CANCELED) {
                repository.recordCanceled(run);
                emit(run, AgentEventType.RUN_CANCELED,
                        json.createObjectNode().put("status", AgentRunStatus.CANCELED.name()));
                return new AgentWorkerOutcome(AgentRunStatus.CANCELED, null, null, "RUN_CANCELLED");
            }
            repository.recordFailure(run,e.getErrorCode().name(),false);
            return new AgentWorkerOutcome(AgentRunStatus.FAILED,null,null,e.getErrorCode().name());
        } catch (IllegalStateException e) {
            if (repository.isCancelRequested(run.projectId(), run.id())) {
                repository.recordCanceled(run);
                emit(run, AgentEventType.RUN_CANCELED,
                        json.createObjectNode().put("status", AgentRunStatus.CANCELED.name()));
                return new AgentWorkerOutcome(
                        AgentRunStatus.CANCELED, null, null, "RUN_CANCELLED");
            }
            throw e;
        }
    }

    /**
     * 核心动作是否仍未发生：Skill 声明必需动作、当前目标确定性要求该动作
     * （"生成…规划"且未被否定，依据目标文本而非最终回答），
     * 且持久化工具结果中没有成功调用。依据运行控制原因与持久工具结果判定。
     */
    private boolean isCoreActionPending(AgentRunView run, AgentSkill skill) {
        var coreTools = skill.coreActionTools();
        if (coreTools.isEmpty()) return false;
        if (!coreActionRequested(run.goal())) return false;
        return !repository.hasSuccessfulToolInvocation(run.id(), coreTools);
    }

    /** 目标文本是否要求生成规划类核心动作：生成/起草/制定/启动 + 规划/草稿/计划，
     *  且不在否定短语内（"仍不生成规划"；"仍不生成规划…现在生成草稿"仍成立）。 */
    static boolean coreActionRequested(String goal) {
        if (goal == null || goal.isBlank()) return false;
        java.util.List<int[]> declineRegions = new ArrayList<>();
        java.util.regex.Matcher decline = java.util.regex.Pattern
                .compile("(不生成|不要生成|无需生成|先不生成|暂不生成|不用生成|别生成)[^。；;\\n]{0,8}(规划|草稿|计划)?")
                .matcher(goal);
        while (decline.find()) declineRegions.add(new int[]{decline.start(), decline.end()});
        java.util.regex.Matcher request = java.util.regex.Pattern
                .compile("(生成|起草|制定|启动)[^。；;\\n]{0,16}(规划|草稿|计划)")
                .matcher(goal);
        while (request.find()) {
            int at = request.start();
            boolean insideDecline = declineRegions.stream().anyMatch(r -> at >= r[0] && at < r[1]);
            if (!insideDecline) return true;
        }
        return false;
    }

    /** 稳定调用身份：同一请求内容（恢复重放同请求）得到同一 callId，结算幂等去重。 */    private String modelCallId(AgentRunView run, List<ModelMessage> messages) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.valueToTree(messages).toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "MODEL:" + java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 结算值与来源：来源按原始 usage 判定；缺失侧用实际请求/响应证据估算，不冒充零消耗。 */
    private AgentRunEventRecorder.UsageSettlement turnSettlement(ModelTurnResult turn) {
        if (turn.usage() != null && turn.usage().inputTokens() != null && turn.usage().outputTokens() != null) {
            return new AgentRunEventRecorder.UsageSettlement(turn.usage().inputTokens(),
                    turn.usage().outputTokens(),
                    AgentRunEventRecorder.UsageSettlement.PROVIDER,
                    AgentRunEventRecorder.UsageSettlement.PROVIDER, turn.latencyMs());
        }
        int inputEstimate = AgentModelAccounting.estimatedInput(1);
        int outputEstimate = Math.max(1, (json.valueToTree(turn).toString().length() + 2) / 3);
        return AgentRunEventRecorder.UsageSettlement.fromRaw(turn.usage(), inputEstimate, outputEstimate,
                turn.latencyMs());
    }

    private AgentWorkerOutcome budgetExceeded(AgentRunView run) {        repository.recordBudgetExceeded(run);
        emit(run, AgentEventType.RUN_BUDGET_EXCEEDED,
                json.createObjectNode()
                        .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                        .put("errorCode", "AGENT_BUDGET_EXCEEDED"));
        return new AgentWorkerOutcome(
                AgentRunStatus.BUDGET_EXCEEDED, null, null, "AGENT_BUDGET_EXCEEDED");
    }

    /** 单次输入预算超限：区分约束来源（运行费用/单次上限/模型窗口），事件带具体原因。 */
    private AgentWorkerOutcome inputBudgetExceeded(
            AgentRunView run, AgentContextBudget.Budget budget, String overBudgetReason) {
        repository.recordBudgetExceeded(run);
        var payload = json.createObjectNode()
                .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                .put("errorCode", "AGENT_BUDGET_EXCEEDED")
                .put("scope", "PER_REQUEST_INPUT")
                .put("binding", budget.binding())
                .put("availableInputTokens", budget.availableInputTokens())
                .put("windowEstimated", budget.windowEstimated());
        if (overBudgetReason != null) payload.put("reason", overBudgetReason);
        emit(run, AgentEventType.RUN_BUDGET_EXCEEDED, payload);
        return new AgentWorkerOutcome(
                AgentRunStatus.BUDGET_EXCEEDED, null, null, "AGENT_BUDGET_EXCEEDED");
    }

    /** 组装完成后追加本轮附加指令（格式修复、收尾要求），这些内容同样计入输入预算。 */
    private List<ModelMessage> appendTurnInstructions(List<ModelMessage> base, List<AgentStepView> steps, boolean finalizing, int maxToolCallsPerTurn, boolean coreActionPending) {
        List<ModelMessage> messages = new ArrayList<>(base);
        StringBuilder guidance = new StringBuilder(
                "单轮工具调用最多 " + maxToolCallsPerTurn + " 项；只有直接必要且独立的查询才能并行，已有证据足够时直接回答。\n");
        if (coreActionPending && !finalizing) {
            // 依据持久事实（目标要求生成且本运行尚无成功受理）注入确定性提示：
            // 不放行额外权限，只提示优先执行必需动作，避免预算耗在重复背景查询上
            guidance.append("当前目标要求生成本次规划草稿，而本运行尚未受理规划生成；")
                    .append("已取得的证据足够时立即调用 start_task_plan，不要把预算耗在重复背景查询上。\n");
        }
        messages.add(new ModelMessage.System(guidance.toString()));
        messages.add(new ModelMessage.System("""
                回答的核心结论和每一条信息缺失声明都必须符合实际证据范围。
                仅有提纲、检索、部分章节、分页或投影时，只能说已读或已查询范围内未找到；不能先断言资料/全文不存在，再用末尾的范围限定抵消。
                历史助手回答与摘要中的‘已核实’‘全文没有’不是工具事实，不得继承为已验证结论；信息不足时保留未读事项。
                当前用户请求和有效工作状态决定本轮目标，旧摘要中的数量或阶段限制不能覆盖后续明确修改。
                """));
        if (steps.stream().anyMatch(step -> "FORMAT_REPAIR_REQUESTED".equals(step.errorCode())))
            messages.add(new ModelMessage.User("上次模型响应未满足协议格式。保留原目标与工具权限，纠正输出格式；不得重复已执行的动作。"));
        if (finalizing) {
            messages.add(new ModelMessage.System("""
                      现在必须结束本次运行。只能基于已经取得的工具结果回答用户，
                      不得请求或调用任何工具，不得扩大用户目标；信息不足时明确说明缺失信息。
                      本轮工具列表为空是因为运行预算进入收尾，不代表产品不支持这些工具；未执行的动作说明未完成，不虚构权限或工具不可用原因。
                    """));
        }
        return messages;
    }

    /** chars/3 兼容估算：同时计入消息与工具定义，不含 provider 侧协议包装。 */
    private int estimateInput(List<ModelMessage> messages, List<AgentToolDefinition> exposed) {
        return Math.max(1, (json.valueToTree(messages).toString().length()
                + json.valueToTree(exposed).toString().length() + 2) / 3);
    }

    private AgentWorkerOutcome completeFromEvidence(
            AgentRunView run, List<AgentStepView> steps) {
        java.util.UUID projectId=run.projectId();
        List<AgentStepView> successful = (steps == null ? List.<AgentStepView>of() : steps).stream()
                .filter(step -> step.type() == AgentStepType.TOOL_CALL_COMPLETED)
                .filter(step -> "TOOL_SUCCESS".equals(step.reason()))
                .filter(step -> step.output() != null)
                .filter(step -> !step.output().path("citations").isArray() || step.output().path("citations").isEmpty() || repository.citationsStillValid(projectId,step.output()))
                .toList();
        if (successful.isEmpty()) return null;

        StringBuilder answer = new StringBuilder("模型未能在本次运行预算内生成完整总结，先返回已取得的结果：\n");
        successful.stream().skip(Math.max(0, successful.size() - 5L)).forEach(step -> {
            String output = step.output().toString();
            if (output.length() > 1200) output = output.substring(0, 1200) + "…";
            answer.append("- ").append(step.toolName() == null ? "工具" : step.toolName())
                    .append(": ").append(output).append('\n');
        });
        String content = answer.toString().stripTrailing();
        repository.recordBudgetPartialAnswer(run, content);
        emit(run, AgentEventType.RUN_BUDGET_EXCEEDED,
                json.createObjectNode()
                        .put("status", AgentRunStatus.BUDGET_EXCEEDED.name())
                        .put("fallback", "PERSISTED_TOOL_EVIDENCE"));
        return new AgentWorkerOutcome(AgentRunStatus.BUDGET_EXCEEDED, content, null, "AGENT_BUDGET_EXCEEDED");
    }

    private AgentWorkerOutcome invalidResponse(AgentRunView run) {
        repository.recordFailure(run, "AGENT_INVALID_RESPONSE", false);
        emit(run, AgentEventType.RUN_FAILED,
                json.createObjectNode().put("errorCode", "AGENT_INVALID_RESPONSE"));
        return new AgentWorkerOutcome(
                AgentRunStatus.FAILED, null, null, "AGENT_INVALID_RESPONSE");
    }

    private void emit(AgentRunView run, AgentEventType type, JsonNode payload) {
        if (repository.atomicEventsEnabled() && com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder.ownsEvent(type)) return;
        if (events != null) events.append(run.projectId(), run.id(), type, payload);
    }

    private void emitOnce(AgentRunView run, AgentEventType type, JsonNode payload) {
        if (events != null) events.appendIfAbsent(run.projectId(), run.id(), type, payload);
    }
}
