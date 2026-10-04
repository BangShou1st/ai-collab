package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 运行过程中的事件与状态持久化。
 *
 * <p>从 {@link AgentRepository} 拆出：会话/运行的查询保留在 AgentRepository，
 * 所有"记录一次执行事件并推进运行状态"的写路径集中在这里，
 * 共享 appendStep、预算推进、父运行恢复等规则。</p>
 */
@Repository
public class AgentRunEventRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.shitulelv.aicollab.agent.application.runtime.AgentEventService events;
    public boolean atomicEventsEnabled() { return events != null; }
    public static boolean ownsEvent(com.shitulelv.aicollab.agent.domain.model.AgentEventType type) {
        return java.util.Set.of("MODEL_COMPLETED","TOOL_CALL_COMPLETED","TOOL_CALL_FAILED","RUN_SUCCEEDED","RUN_FAILED","RUN_CANCELED","RUN_BUDGET_EXCEEDED","WAITING_FOR_USER_INPUT","APPROVAL_REQUESTED","APPROVAL_UPDATED").contains(type.name());
    }
    private void event(AgentRunView run, String type, JsonNode payload) {
        if (events != null) events.append(run.projectId(),run.id(),com.shitulelv.aicollab.agent.domain.model.AgentEventType.valueOf(type),payload);
    }

    public AgentRunEventRecorder(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 会话摘要尝试的持久化标记：每次尝试先落一行（reason=CONTEXT_SUMMARY），
     * 同一运行的尝试次数据此校验，服务重启不能绕过上限。
     */
    @Transactional
    public UUID beginSummaryAttempt(AgentRunView run) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY")
                .put("status", "ATTEMPTED");
        return jdbc.queryForObject("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                SELECT ?,coalesce(max(sequence_no),0)+1,'MODEL_REQUEST','context_summary',
                  ?::jsonb,?::jsonb,'CONTEXT_SUMMARY'
                FROM agent_step WHERE run_id=?
                RETURNING id
                """, UUID.class, run.id(), beginInfo.toString(), beginInfo.toString(), run.id());
    }

    /**
     * 完成摘要尝试并单独记账：token 计入<b>所属运行</b>的总预算（同样受 max 封顶），
     * usage 缺失时按实际输入/输出字符保守估算，绝不按零计入；
     * outcome 区分 COMMITTED / CAS_CONFLICT / EMPTY / FAILED。
     *
     * <p>结算规则：attemptId 是 agent_step 的 ID（不是运行 ID），运行 ID 从步骤行取回；
     * 仅当步骤仍处于 ATTEMPTED 时才转换终态并记账，重复完成不会重复扣费。</p>
     */
    @Transactional
    public void completeSummaryAttempt(UUID attemptId, String outcome, String model,
            Integer inputTokens, Integer outputTokens, boolean estimated, Long latencyMs) {
        var output = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY")
                .put("status", outcome)
                .put("model", model == null ? "unknown" : model);
        // ATTEMPTED → 终态只允许转换一次：转换零行说明已结算，直接跳过防止重复扣费
        int transitioned = jdbc.update("""
                UPDATE agent_step SET output_json=?::jsonb,
                  prompt_tokens=?,completion_tokens=?,token_usage_estimated=?,latency_ms=?
                WHERE id=? AND output_json->>'status'='ATTEMPTED'
                """, output.toString(), inputTokens, outputTokens, estimated,
                latencyMs == null ? null : latencyMs.intValue(), attemptId);
        if (transitioned == 0) return;
        jdbc.update("""
                UPDATE agent_run SET
                  input_tokens_used=LEAST(max_input_tokens, input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens, output_tokens_used+?),
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now()
                WHERE id=(SELECT run_id FROM agent_step WHERE id=?)
                """, inputTokens == null ? 0 : inputTokens, outputTokens == null ? 0 : outputTokens,
                estimated, attemptId);
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        appendErrorStep(run, "AGENT_BUDGET_EXCEEDED");
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  error_code='AGENT_BUDGET_EXCEEDED',finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
        resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED");
        event(run,"RUN_BUDGET_EXCEEDED",json.createObjectNode().put("status","BUDGET_EXCEEDED"));
    }

    @Transactional
    public void recordBudgetPartialAnswer(AgentRunView run, String content) {
        recordBudgetExceeded(run);
        jdbc.update("""
                INSERT INTO agent_message(session_id,run_id,role,content)
                VALUES (?,?,'ASSISTANT',?)
                """, run.sessionId(), run.id(), content);
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run, ChatCompletionResult completion) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR','Model response exceeded remaining budget',?,?,?,?,?,
                  'AGENT_BUDGET_EXCEEDED')
                """, run.id(), sequence, completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=?,error_code='AGENT_BUDGET_EXCEEDED',
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, completion.promptTokens()==null ? com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(1) : completion.promptTokens(),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                run.projectId(), run.id(), run.version()));
        resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED");
        event(run,"RUN_BUDGET_EXCEEDED",json.createObjectNode().put("status","BUDGET_EXCEEDED"));
    }

    @Transactional
    public void recordDecisionFailure(
            AgentRunView run, ChatCompletionResult completion, String errorCode, String reason) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?)
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()), errorCode);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='FAILED',steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=?,error_code=?,finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                errorCode, run.projectId(), run.id(), run.version()));
        resumeParent(run, "FAILED", errorCode);
    }

    @Transactional
    public void recordFailure(AgentRunView run, String errorCode, boolean retryable) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        appendErrorStep(run, errorCode);
        // 如果可重试但已达到最大重试次数（10），直接标记为 FAILED 避免死循环
        boolean modelRetry=retryable && !"FORMAT_REPAIR_REQUESTED".equals(errorCode);
        boolean finalFailure = !retryable || (modelRetry && run.retryCount() >= 2)
                || Boolean.TRUE.equals(jdbc.queryForObject("SELECT active_elapsed_ms>=300000 FROM agent_run WHERE id=?",Boolean.class,run.id()));
        if (finalFailure) markBatchHandled(run.id());
        if (!finalFailure && !"FORMAT_REPAIR_REQUESTED".equals(errorCode)) jdbc.update("INSERT INTO agent_recovery_counter(run_id,kind,attempts) VALUES (?,'MODEL_RETRY',1) ON CONFLICT(run_id,kind) DO UPDATE SET attempts=agent_recovery_counter.attempts+1",run.id());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,error_code=?,
                  retry_count=LEAST(retry_count+CASE WHEN ? THEN 1 ELSE 0 END, 10),
                  retry_after=CASE WHEN ? AND NOT ? THEN now()+interval '30 seconds' ELSE NULL END,
                  finished_at=CASE WHEN ? THEN now() ELSE NULL END,
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, finalFailure ? "FAILED" : "FAILED_RETRYABLE", errorCode,
                modelRetry, retryable, finalFailure, finalFailure,
                run.projectId(), run.id(), run.version()));
        if (finalFailure) resumeParent(run, "FAILED", errorCode);
        event(run,"RUN_FAILED",json.createObjectNode().put("status",finalFailure ? "FAILED" : "FAILED_RETRYABLE").put("errorCode",errorCode).put("retryable",!finalFailure));
    }

    /**
     * 记录取消状态。状态为 CANCELED，error_code 为 RUN_CANCELLED。
     * 不复用 recordFailure 方法，确保状态一致。
     */
    @Transactional
    public void recordCanceled(AgentRunView run) {
        if (findRun(run.projectId(), run.id()).map(AgentRunView::status).orElse(null) == AgentRunStatus.CANCELED) return;
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), true);
        accountActiveTime(run.id());
        jdbc.update("UPDATE agent_tool_invocation SET status='CANCELED',result_json='{\"status\":\"CANCELED\"}'::jsonb WHERE run_id=? AND status='PENDING'", run.id());
        markBatchHandled(run.id());
        int updated = jdbc.update("""
                UPDATE agent_run SET status='CANCELED', error_code='RUN_CANCELLED',
                  finished_at=now(), lease_owner=NULL, lease_expires_at=NULL,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, run.projectId(), run.id());
        if (updated == 0) {
            AgentRunStatus status = findRun(run.projectId(), run.id())
                    .map(AgentRunView::status)
                    .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
            if (status == AgentRunStatus.CANCELED) return;
            throw new IllegalStateException("Agent 运行已进入不可取消状态: " + status);
        }
        appendErrorStep(run, "RUN_CANCELLED");
        resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED");
        event(run,"RUN_CANCELED",json.createObjectNode().put("status","CANCELED"));
    }

    @Transactional
    public void recordInvalidDecision(
            AgentRunView run, ChatCompletionResult completion, String reason) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,'AGENT_INVALID_DECISION')
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        boolean retry = !run.correctionAttempted();
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,correction_attempted=true,
                  steps_used=steps_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,token_usage_estimated=?,
                  error_code='AGENT_INVALID_DECISION',
                  lease_owner=NULL,lease_expires_at=NULL,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, retry ? "QUEUED" : "FAILED",
                tokens(completion.promptTokens(), completion.content()),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                retry, run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public void recordFinal(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.FinalAnswer answer) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,?,?,?,?)
                """, run.id(), sequence, jsonString(answer),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,?::jsonb,?::jsonb)
                """, run.sessionId(), run.id(), answer.answer(),
                answer.citations().toString(), answer.inferences().toString());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',steps_used=steps_used+1,
                  input_tokens_used=input_tokens_used+?,output_tokens_used=output_tokens_used+?,
                  token_usage_estimated=?,model_provider=?,model_name=?,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
        resumeParent(run, "SUCCEEDED", answer.answer());
    }

    @Transactional
    public void recordToolResult(
            AgentRunView run,
            ChatCompletionResult completion,
            AgentDecision.CallTool call,
            JsonNode result) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason,
                  prompt_tokens,completion_tokens,token_usage_estimated,latency_ms)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?,?,?,?,?)
                """, run.id(), sequence, call.tool(), call.arguments().toString(),
                result.toString(), truncate(call.reason(), 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',steps_used=steps_used+1,
                  tool_calls_used=tool_calls_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,token_usage_estimated=?,
                  model_provider=?,model_name=?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public AgentRunView recordDelegation(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.Delegate delegate) {
        if (run.depth() != 0 || run.childrenUsed() >= run.maxChildren()) {
            throw new IllegalStateException("Agent 子运行预算已耗尽");
        }
        UUID childId = UUID.randomUUID();
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,input_json,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms)
                VALUES (?,?,'DELEGATION_REQUESTED',
                  jsonb_build_object('role',?,'objective',?),?,?,?,?,?)
                """, run.id(), sequence, delegate.role(), delegate.objective(),
                delegate.objective(), completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        int promptTokens = tokens(completion.promptTokens(), "");
        int outputTokens = tokens(completion.completionTokens(), completion.content());
        int childSteps = run.maxSteps() - run.stepsUsed() - 1;
        int childTools = run.maxToolCalls() - run.toolCallsUsed();
        int childInputs = run.maxInputTokens() - run.inputTokensUsed() - promptTokens;
        int childOutputs = run.maxOutputTokens() - run.outputTokensUsed() - outputTokens;
        if (childSteps < 1 || childInputs < 1 || childOutputs < 1) {
            throw new IllegalStateException("Agent 没有可分配给子运行的剩余预算");
        }
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?)
                RETURNING *
                """, AgentRunMappers.runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), delegate.role(), delegate.objective(),
                childSteps, childTools, childInputs, childOutputs);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='CREATED',steps_used=steps_used+1,
                  children_used=children_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, promptTokens, outputTokens,
                run.projectId(), run.id(), run.version()));
        return child;
    }

    public boolean updateStatus(
            UUID projectId, UUID runId, int version,
            AgentRunStatus expected, AgentRunStatus target, String errorCode) {
        boolean changed = jdbc.update("""
                UPDATE agent_run
                SET status=?,error_code=?,lease_owner=NULL,lease_expires_at=NULL,
                    finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELED','BUDGET_EXCEEDED')
                                     THEN now() ELSE finished_at END,
                    updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status=?
                """, target.name(), errorCode, target.name(),
                projectId, runId, version, expected.name()) == 1;
        if (changed && target == AgentRunStatus.CANCELED) {
            findRun(projectId, runId).ifPresent(
                    run -> resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED"));
        }
        return changed;
    }

    /**
     * 更新 Run 的计划。
     */
    @Transactional
    public void updatePlan(UUID projectId, UUID runId, int version, String planJson) {
        AgentLeaseScope.verify(jdbc, projectId, runId, false);
        jdbc.update("""
                UPDATE agent_run SET plan_json=?::jsonb,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=?
                """, planJson, projectId, runId, version);
    }

    /**
     * 重新排队 Run。
     */
    @Transactional
    public void requeueRun(AgentRunView run) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
    }

    /**
     * 记录模型轮次。同时更新 Run 的 token 预算计数和版本。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        int inputTokens = estimateInputTokens(turn);
        int outputTokens = tokens(turn.usage() == null ? null : turn.usage().outputTokens(), jsonString(turn));
        boolean estimated = turn.usage() == null || turn.usage().inputTokens() == null || turn.usage().outputTokens() == null;

        // 先验证版本和状态，避免版本冲突时留下孤立 Step
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens, input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens, output_tokens_used+?),
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, inputTokens, outputTokens, estimated,
                run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,model_provider,model_name)
                VALUES (?,?,'MODEL_TURN',?,?,?,?,?,?,?)
                """, run.id(), sequence,
                truncate(turn.content(), 2000),
                inputTokens,
                turn.usage() != null ? turn.usage().outputTokens() : null,
                estimated,
                boundedLatency(turn.latencyMs()),
                truncate(turn.provider(), 80),
                truncate(turn.model(), 120));
        jdbc.update("UPDATE agent_step SET output_json=?::jsonb WHERE run_id=? AND sequence_no=?", jsonString(turn), run.id(), sequence);
        for (int ordinal = 0; ordinal < turn.toolCalls().size(); ordinal++) {
            var call = turn.toolCalls().get(ordinal);
            UUID invocationId = UUID.nameUUIDFromBytes((run.id() + ":" + sequence + ":" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status)
                    VALUES (?,?,?,?,?,?,?::jsonb,'PENDING')
                    """, run.id(), sequence, ordinal, invocationId, call.id(), call.name(), call.arguments().toString());
        }

        event(run,"MODEL_COMPLETED",json.createObjectNode().put("stepSequence",sequence).put("finishReason",turn.finishReason().name()).put("toolCallCount",turn.toolCalls().size()));
        // 返回更新后的 Run
        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录工具结果。同时更新 Run 的 tool_calls_used 和版本。
     */
    @Transactional
    public AgentRunView recordToolResult(AgentRunView run, String toolName,
                                  JsonNode arguments, JsonNode result, boolean isError) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        // 先验证版本和状态，避免版本冲突时留下孤立 Step
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+1,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        // 7 columns, type hardcoded = 6 bind params. ::jsonb on input_json and output_json.
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?)
                """, run.id(), sequence, toolName,
                arguments.toString(),
                result.toString(),
                isError ? "TOOL_ERROR" : "TOOL_SUCCESS");
        if (arguments.hasNonNull("toolCallId")) {
            jdbc.update("""
                    UPDATE agent_tool_invocation SET status=?,result_json=?::jsonb,updated_at=now()
                    WHERE run_id=? AND tool_call_id=? AND tool_name=? AND arguments_json=?::jsonb
                      AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                    """, isError ? result.path("status").asText("FAILED") : "SUCCEEDED", result.toString(),
                    run.id(), arguments.path("toolCallId").asText(), toolName, arguments.path("arguments").toString(), run.id());
        }
        event(run,isError ? "TOOL_CALL_FAILED" : "TOOL_CALL_COMPLETED",json.createObjectNode()
                .put("callId",arguments.path("toolCallId").asText()).put("toolName",toolName)
                .put("invocationId",jdbc.queryForList("SELECT invocation_id::text FROM agent_tool_invocation WHERE run_id=? AND tool_call_id=? AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')",String.class,run.id(),arguments.path("toolCallId").asText(),run.id()).stream().findFirst().orElse(""))
                .put("status",result.path("status").asText(isError ? "FAILED" : "SUCCEEDED"))
                .put("effect",result.path("effect").asText())
                .put("errorCode",result.path("error").isObject() ? result.path("error").path("code").asText() : result.path("error").asText())
                .put("stepSequence",sequence)
                .set("taskFacts", !isError && "list_tasks".equals(toolName)
                        ? result.path("data").path("taskFacts") : json.createArrayNode()));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录最终答案。使用 ObjectMapper 序列化 JSONB，确保中文、引号、换行等正确保存。
     * 先检查版本，再写入数据，避免版本冲突时留下孤立数据。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        // 先验证版本和状态，避免版本冲突时留下孤立 Step/Message
        int updated = jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',
                  steps_used=steps_used+1,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        int sequence = nextSequence(run.id());

        // 使用 ObjectMapper 序列化为 JSON 对象，确保 JSONB 正确
        var evidence = new java.util.LinkedHashMap<UUID, AgentCitation>();
        for (String value : jdbc.queryForList("""
                SELECT c.value::text FROM agent_step s
                CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(s.output_json->'citations')='array' THEN s.output_json->'citations' ELSE '[]'::jsonb END) c(value)
                WHERE s.run_id=? AND s.type='TOOL_CALL_COMPLETED' AND s.reason='TOOL_SUCCESS'
                  AND coalesce(s.output_json->>'status','SUCCEEDED')='SUCCEEDED'
                  AND EXISTS (
                    SELECT b.id FROM document_body_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId' AND d.project_id=? AND d.status<>'DELETING'
                    UNION ALL SELECT b.id FROM document_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId' AND d.project_id=? AND d.status='READY')
                ORDER BY s.sequence_no
                """, String.class, run.id(), run.projectId(), run.projectId())) {
            try { var citation=json.readValue(value,AgentCitation.class); evidence.putIfAbsent(citation.chunkId(),citation); }
            catch (JsonProcessingException malformed) { /* Only persisted, structured source identities are exposed. */ }
        }
        // Source cards describe material actually read this run, never model-invented identifiers.
        var availableCitations=evidence.values().stream().limit(50).toList();
        String outputJson;
        try {
            ObjectNode outputNode = json.createObjectNode();
            outputNode.put("answer", content);
            outputNode.set("citations", json.valueToTree(availableCitations));
            outputNode.set("inferences", json.createArrayNode());
            outputJson = json.writeValueAsString(outputNode);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 结果无法序列化", e);
        }

        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb)
                """, run.id(), sequence, outputJson);
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,?::jsonb,'[]'::jsonb)
                """, run.sessionId(), run.id(), content, json.valueToTree(availableCitations).toString());
        event(run,"RUN_SUCCEEDED",json.createObjectNode().put("status","SUCCEEDED"));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录等待用户输入状态。
     */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        AgentWorkingState.question(jdbc,json,run.sessionId(),question);
        markBatchHandled(run.id());
        // 先验证版本和状态
        int updated = jdbc.update("""
                UPDATE agent_run SET status='WAITING_FOR_USER_INPUT',
                  finished_at=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,'WAITING_FOR_USER_INPUT')
                """, run.id(), sequence, jsonString(Map.of("question", question)));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,'[]'::jsonb,'[]'::jsonb)
                """, run.sessionId(), run.id(), question);
        event(run,"WAITING_FOR_USER_INPUT",json.createObjectNode().put("question",question));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 继续等待用户输入的 Run。
     */
    @Transactional
    public AgentRunView continueRun(AgentRunView run, String userResponse) {
        // 先验证版本和状态
        int updated = jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  finished_at=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='WAITING_FOR_USER_INPUT'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        // 记录用户回复并取得真实消息 ID，同事务内关联到工作状态
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                RETURNING id
                """, UUID.class, run.sessionId(), run.id(), userResponse);
        AgentWorkingState.appendUser(jdbc,json,run.sessionId(),userResponse,messageId);

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    private Optional<AgentRunView> findRun(UUID projectId, UUID runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND id=?",
                AgentRunMappers.runMapper(), projectId, runId).stream().findFirst();
    }

    private void markBatchHandled(UUID runId) {
        jdbc.update("UPDATE agent_step SET output_json=jsonb_set(output_json,'{batchHandled}','true'::jsonb) WHERE run_id=? AND type='MODEL_TURN' AND output_json IS NOT NULL", runId);
        jdbc.update("UPDATE agent_tool_invocation SET status='SKIPPED',result_json='{\"status\":\"SKIPPED\",\"error\":\"BATCH_ENDED\"}'::jsonb,updated_at=now() WHERE run_id=? AND status='PENDING'", runId);
    }
    private void accountActiveTime(UUID runId) {
        jdbc.update("UPDATE agent_run SET active_elapsed_ms=active_elapsed_ms+CASE WHEN claim_started_at IS NULL THEN 0 ELSE GREATEST(0,(extract(epoch FROM(now()-claim_started_at))*1000)::bigint) END,claim_started_at=NULL WHERE id=?",runId);
    }

    private void appendErrorStep(AgentRunView run, String errorCode) {
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,error_code)
                VALUES (?,?,'ERROR',?)
                """, run.id(), nextSequence(run.id()), errorCode);
    }

    private int nextSequence(UUID runId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?
                """, Integer.class, runId);
        return value == null ? 1 : value;
    }

    private String jsonString(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 结果无法序列化", exception);
        }
    }

    private static int tokens(Integer reported, String text) {
        if (reported != null) return reported;
        int codePoints = text == null ? 0 : text.codePointCount(0, text.length());
        return Math.max(1, (codePoints + 2) / 3);
    }

    private static int boundedLatency(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return null;
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void requireRunUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Agent 运行已被其他 worker 修改");
        }
    }

    private void resumeParent(AgentRunView child, String status, String content) {
        if (child.parentRunId() == null) return;
        AgentRunView usage = findRun(child.projectId(), child.id()).orElse(child);
        int sequence = nextSequence(child.parentRunId());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'DELEGATION_COMPLETED',
                  jsonb_build_object('childRunId',?,'status',?,'content',?),?)
                """, child.parentRunId(), sequence, child.id(), status, content,
                "Specialist child run completed");
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  steps_used=LEAST(max_steps,steps_used+?),
                  tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(),version=version+1
                WHERE id=? AND status='CREATED'
                """, usage.stepsUsed(), usage.toolCallsUsed(), usage.inputTokensUsed(),
                usage.outputTokensUsed(), usage.tokenUsageEstimated(), child.parentRunId());
    }

    private static int estimateInputTokens(ModelTurnResult turn) {
        if (turn.usage() != null && turn.usage().inputTokens() != null) {
            return turn.usage().inputTokens();
        }
        return com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(Math.max(1,(turn.content()==null ? 0 : turn.content().length())/3));
    }
}
