package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.DelegatedResearchCoverage;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
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
    /**
     * 推进步记账（V63 预算语义兼容标记）：COMBINED（旧语义，既有行默认）下工具结果
     * 逐项占用推进步；SEPARATED（新语义，新根运行默认）下一次模型轮计一次推进、
     * 最终回答落库保留一次收口，工具结果只计 tool_calls_used——持久化步骤/事件/invocation
     * 不变，只改计数语义。子运行继承父运行语义，旧运行恢复/暂停续跑保持 COMBINED。
     * 分支表达式内联在各 UPDATE 的 steps_used 赋值处。
     */

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.shitulelv.aicollab.agent.application.runtime.AgentEventService events;
    public boolean atomicEventsEnabled() { return events != null; }
    public static boolean ownsEvent(com.shitulelv.aicollab.agent.domain.model.AgentEventType type) {
        return java.util.Set.of("MODEL_COMPLETED","TOOL_CALL_COMPLETED","TOOL_CALL_FAILED","RUN_SUCCEEDED","RUN_FAILED","RUN_CANCELED","RUN_BUDGET_EXCEEDED","WAITING_FOR_USER_INPUT","APPROVAL_REQUESTED","APPROVAL_UPDATED","RUN_PAUSE_REQUESTED","RUN_PAUSED","RUN_RESUMED").contains(type.name());
    }
    private void event(AgentRunView run, String type, JsonNode payload) {
        event(run.projectId(), run.id(), type, payload);
    }
    private void event(java.util.UUID projectId, java.util.UUID runId, String type, JsonNode payload) {
        if (events != null) events.append(projectId, runId, com.shitulelv.aicollab.agent.domain.model.AgentEventType.valueOf(type), payload);
    }

    public AgentRunEventRecorder(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 会话摘要尝试的持久化标记：每次尝试先落一行（reason=CONTEXT_SUMMARY），
     * 同一运行的尝试次数据此校验，服务重启不能绕过上限。
     *
     * <p>与 {@link #beginModelCall} 相同，本方法同时是摘要出站请求的<b>持久化准入边界</b>：
     * 暂停意图先落库或运行已离开 RUNNING（含过期 claim 被租约拦截）时，不创建请求身份、
     * 不发模型请求（抛 AGENT_RUN_PAUSED / AGENT_RUN_CANCELED，由调用方按控制结果处理）。</p>
     */
    @Transactional
    public UUID beginSummaryAttempt(AgentRunView run) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY")
                .put("status", "ATTEMPTED");
        return insertSummaryCallStep(run, "context_summary", "CONTEXT_SUMMARY", beginInfo);
    }

    /**
     * 摘要重压缩调用的持久化标记（reason=CONTEXT_SUMMARY_RECOMPRESS）：
     * 每次实际模型请求都有独立调用身份与结算状态；重试/恢复不得绕过
     * CONTEXT_SUMMARY 的每运行尝试上限，也不得重复结算。
     * 准入边界同 {@link #beginSummaryAttempt}：重压缩是新的出站请求，
     * 暂停意图先落库时同样不创建身份、不发出。
     */
    @Transactional
    public UUID beginSummaryRecompressAttempt(AgentRunView run) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY_RECOMPRESS")
                .put("status", "ATTEMPTED");
        return insertSummaryCallStep(run, "context_summary", "CONTEXT_SUMMARY_RECOMPRESS", beginInfo);
    }

    /**
     * 运行研究轨迹窗口压缩（RUN_CONTEXT scope）的持久化请求身份。
     *
     * <p>与主会话摘要（{@code CONTEXT_SUMMARY}）共用生成/校验/记账规则与同一准入边界，
     * 但作用域不同：本 scope 压缩的是<b>本运行自己的研究轨迹</b>（模型轮与工具结果），
     * 不读写 {@code agent_session.working_state.summary}，因此子运行只生成自己的
     * RUN_CONTEXT、不触发也不改写主会话摘要。</p>
     *
     * <p>身份先落库再出站，成功提交才推进覆盖；每个
     * {@code scope + 源边界 + 目标修订/摘要版本} 至多一个有效周期。</p>
     */
    @Transactional
    public UUID beginRunContextAttempt(AgentRunView run, int cycle, int fromSequence, int throughSequence) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "RUN_CONTEXT_SUMMARY")
                .put("status", "ATTEMPTED")
                .put("scope", "RUN_CONTEXT")
                .put("runId", run.id().toString())
                .put("cycle", cycle)
                .put("sourceFromSequence", fromSequence)
                .put("sourceThroughSequence", throughSequence);
        return insertSummaryCallStep(run, "run_context_summary", "RUN_CONTEXT_SUMMARY", beginInfo);
    }

    /** 完成一次 RUN_CONTEXT 压缩尝试：覆盖边界只在成功提交时写入（失败不推进）。 */
    @Transactional
    public void completeRunContextAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note, JsonNode committedSummary) {
        completeSummaryCallStep(attemptId, "RUN_CONTEXT_SUMMARY", outcome, model, usage, note);
        if (committedSummary == null) return;
        // 覆盖事实与本运行一致：把后端计算出的源范围与有效摘要写进该步骤的 output_json，
        // 模型不能填写"这些步骤全读过"（覆盖由实际送入摘要请求的记录计算）。
        jdbc.update("""
                UPDATE agent_step SET output_json = output_json || ?::jsonb
                WHERE id=? AND reason='RUN_CONTEXT_SUMMARY'
                """, committedSummary.toString(), attemptId);
    }

    /**
     * 摘要出站请求的运行行锁准入：与 requestPause 的事务串行化确定提交顺序，
     * 并复用 {@link AgentLeaseScope} 拦截过期 claim。短事务，锁不跨网络等待——
     * 身份行落库提交后模型请求才发出。
     */
    private void admitSummaryCall(AgentRunView run) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        var admission = jdbc.queryForList(
                "SELECT status,pause_requested_at FROM agent_run WHERE id=? FOR UPDATE", run.id());
        if (admission.isEmpty()) throw new IllegalStateException("Agent 运行不存在: " + run.id());
        if (admission.getFirst().get("pause_requested_at") != null) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不再启动新的摘要请求");
        }
        if (!"RUNNING".equals(admission.getFirst().get("status"))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED, "Agent 运行已离开运行状态");
        }
    }

    private UUID insertSummaryCallStep(AgentRunView run, String toolName, String reason, ObjectNode beginInfo) {
        admitSummaryCall(run);
        return jdbc.queryForObject("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                SELECT ?,coalesce(max(sequence_no),0)+1,'MODEL_REQUEST',?,
                  ?::jsonb,?::jsonb,?
                FROM agent_step WHERE run_id=?
                RETURNING id
                """, UUID.class, run.id(), toolName, beginInfo.toString(), beginInfo.toString(), reason, run.id());
    }

    /** 单次实际模型请求的结算值与来源：来源按原始 usage 判定并显式传递，不按数字非空倒推。 */
    public record UsageSettlement(Integer inputTokens, Integer outputTokens,
            String inputBasis, String outputBasis, Long latencyMs) {
        public static final String PROVIDER = "PROVIDER";
        public static final String ESTIMATED = "ESTIMATED";
        public static final String UNKNOWN = "UNKNOWN";

        /** 原始 usage 缺失的侧用实际请求/响应证据估算；连证据都没有时显式 UNKNOWN。 */
        public static UsageSettlement fromRaw(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage,
                int fallbackInput, int fallbackOutput, Long latencyMs) {
            Integer in = usage == null ? null : usage.inputTokens();
            Integer out = usage == null ? null : usage.outputTokens();
            String inBasis = in != null ? PROVIDER : (fallbackInput > 0 ? ESTIMATED : UNKNOWN);
            String outBasis = out != null ? PROVIDER : (fallbackOutput > 0 ? ESTIMATED : UNKNOWN);
            return new UsageSettlement(
                    in != null ? in : Math.max(0, fallbackInput),
                    out != null ? out : Math.max(0, fallbackOutput),
                    inBasis, outBasis, latencyMs);
        }

        /** 无任何可结算证据：显式未知，不得把默认 0 解释成真实零费用。 */
        public static UsageSettlement unknown() {
            return new UsageSettlement(null, null, UNKNOWN, UNKNOWN, null);
        }

        /** 混合来源的统一表示：双方 PROVIDER 才是 PROVIDER，双方 UNKNOWN 才是 UNKNOWN，其余 ESTIMATED。 */
        public String combinedBasis() {
            if (PROVIDER.equals(inputBasis) && PROVIDER.equals(outputBasis)) return PROVIDER;
            if (UNKNOWN.equals(inputBasis) && UNKNOWN.equals(outputBasis)) return UNKNOWN;
            return ESTIMATED;
        }

        public boolean estimated() { return !PROVIDER.equals(combinedBasis()); }

        public int bookedInput() { return inputTokens == null ? 0 : inputTokens; }

        public int bookedOutput() { return outputTokens == null ? 0 : outputTokens; }
    }

    /**
     * 完成摘要尝试并单独记账：token 计入<b>所属运行</b>的总预算（同样受 max 封顶），
     * usage 来源显式传入（见 {@link UsageSettlement}），混合来源在 output_json 里
     * 分侧记录；outcome 区分 COMMITTED / CAS_CONFLICT / EMPTY / FAILED / CANCELED /
     * DOWNSGRADED_UNQUALIFIED，note 记录降级或重压缩结算说明。
     *
     * <p>结算规则：attemptId 是 agent_step 的 ID（不是运行 ID），运行 ID 从步骤行取回；
     * 仅当步骤仍处于 ATTEMPTED 时才转换终态并记账，重复完成不会重复扣费。</p>
     *
     * <p>outcome 另含 PAUSED：重压缩因暂停意图未获准入（控制结果，首次用量照常结算）。</p>
     */
    @Transactional
    public void completeSummaryAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note) {
        completeSummaryCallStep(attemptId, "CONTEXT_SUMMARY", outcome, model, usage, note);
    }

    /** 完成摘要重压缩调用的记账（outcome：SETTLED / FAILED）。 */
    @Transactional
    public void completeSummaryRecompressAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note) {
        completeSummaryCallStep(attemptId, "CONTEXT_SUMMARY_RECOMPRESS", outcome, model, usage, note);
    }

    private void completeSummaryCallStep(UUID attemptId, String purpose, String outcome, String model,
            UsageSettlement usage, String note) {
        String basis = usage.combinedBasis();
        var output = json.createObjectNode()
                .put("purpose", purpose)
                .put("status", outcome)
                .put("model", model == null ? "unknown" : model)
                .put("usageBasis", basis)
                .put("inputTokensBasis", usage.inputBasis())
                .put("outputTokensBasis", usage.outputBasis());
        if (note != null) output.put("note", note);
        if (usage.inputTokens() != null) output.put("inputTokens", usage.inputTokens());
        if (usage.outputTokens() != null) output.put("outputTokens", usage.outputTokens());
        // ATTEMPTED → 终态只允许转换一次：转换零行说明已结算，直接跳过防止重复扣费
        int transitioned = jdbc.update("""
                UPDATE agent_step SET output_json=?::jsonb,
                  prompt_tokens=?,completion_tokens=?,token_usage_estimated=?,usage_basis=?,
                  latency_ms=?
                WHERE id=? AND output_json->>'status'='ATTEMPTED'
                """, output.toString(), usage.bookedInput(), usage.bookedOutput(), usage.estimated(),
                basis, usage.latencyMs() == null ? null : usage.latencyMs().intValue(), attemptId);
        if (transitioned == 0) return;
        // used 保持预算语义（封顶），actual 如实累计：摘要消耗可以超出剩余预算，
        // 真实值不能被 max 遮蔽
        bookRunUsage("UPDATE agent_run SET\n" +
                "                input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),\n" +
                "                output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),\n" +
                "                input_tokens_actual=input_tokens_actual+?,\n" +
                "                output_tokens_actual=output_tokens_actual+?,\n" +
                "                token_usage_estimated=token_usage_estimated OR ?,\n" +
                "                updated_at=now()\n" +
                "              WHERE id=(SELECT run_id FROM agent_step WHERE id=?)",
                usage, attemptId);
    }

    private void bookRunUsage(String sql, UsageSettlement usage, UUID attemptId) {
        jdbc.update(sql, usage.bookedInput(), usage.bookedOutput(),
                usage.bookedInput(), usage.bookedOutput(), usage.estimated(), attemptId);
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
        recordBudgetPartialAnswer(run, content, false);
    }

    /**
     * 收口"上一 claim 已持久化、尚未消费"的模型文本轮次并落地预算部分回答：
     * 结果消费标记与终态写入在同一事务，任一步失败整体回滚——
     * 重复接管、重复推进与旧 worker 迟到提交都不会二次消费同一轮响应。
     */
    @Transactional
    public void recordBudgetPartialAnswer(AgentRunView run, String content, boolean consumePersistedTurn) {
        recordBudgetExceeded(run);
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
        // 子研究运行不向共享会话写 ASSISTANT 消息（与 recordFinal 同一隔离边界）
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(session_id,run_id,role,content)
                    VALUES (?,?,'ASSISTANT',?)
                    """, run.sessionId(), run.id(), content);
        } else if (content != null && !content.isBlank()) {
            // R12b 子运行预算部分回答随回收进入父综合：recordBudgetExceeded 的 resumeParent
            // 先按错误码占位写入 DELEGATION_COMPLETED，这里把证据兜底产出的部分回答回填，
            // 否则子运行已取得的工具证据对父运行不可见（委派失败等于产出全丢，
            // 真实委派实验第三次运行复现）。仅回填错误码占位，不覆盖已有产出内容。
            jdbc.update("""
                    UPDATE agent_step SET output_json = jsonb_set(output_json, '{content}', ?::jsonb)
                    WHERE run_id=? AND type='DELEGATION_COMPLETED'
                      AND output_json->>'childRunId'=?
                      AND output_json->>'content'='AGENT_BUDGET_EXCEEDED'
                    """, jsonString(content), run.parentRunId(), run.id().toString());
        }
    }

    /**
     * 标记已持久化模型轮次的结果被消费（原输出上置 {@code batchHandled}）。
     * 由终态写入同事务调用：只有终态真正提交，该轮次才不再作为"待恢复结果"出现。
     * 工具批次的消费仍走 {@link #markBatchHandled}，两者共用同一标记。
     */
    private void markModelTurnConsumed(UUID runId) {
        jdbc.update("""
                UPDATE agent_step SET output_json=jsonb_set(output_json,'{batchHandled}','true'::jsonb)
                WHERE run_id=? AND type='MODEL_TURN' AND output_json IS NOT NULL
                """, runId);
    }

    /**
     * 输出超限分支：如实结算用量并按原语义进入 BUDGET_EXCEEDED，<b>同时保存已经返回的正文</b>。
     *
     * <p>F8 修复：原先只写 ERROR 与用量，丢弃 {@code completion.content()}，
     * 随后 {@code resumeParent} 只回传错误码 —— 已经发生的模型调用返回的研究文字
     * 对父运行不可见（子运行同样丢失这一轮研究文字）。现在把已返回的无工具正文
     * 有界持久化为<b>部分产出</b>：终态仍如实为 BUDGET_EXCEEDED（不用 SUCCEEDED 粉饰完整性），
     * 真实用量（含提供商上报）如实保留，且<b>不执行</b>该响应提出的任何新工具。</p>
     */
    @Transactional
    public void recordBudgetExceeded(AgentRunView run, ChatCompletionResult completion) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        // 已返回但未及正常收口的正文：有界保存为部分回答（截断只影响落库长度，
        // 不虚构内容、不改终态语义），供父运行回收与用户查看。
        String returned = completion.content() == null ? "" : completion.content().strip();
        boolean hasReturnedText = !returned.isBlank();
        if (hasReturnedText) {
            persistPartialAnswerText(run, truncate(returned, MAX_PARTIAL_ANSWER_CHARS));
        }
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR','Model response exceeded remaining budget',?,?,?,?,?,
                  'AGENT_BUDGET_EXCEEDED')
                """, run.id(), sequence, completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,error_code='AGENT_BUDGET_EXCEEDED',
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, completion.promptTokens()==null ? com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(1) : completion.promptTokens(),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens()==null ? com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(1) : completion.promptTokens(),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                run.projectId(), run.id(), run.version()));
        // 回收：已有部分正文时把正文一并移交父运行（而不是只回传 AGENT_BUDGET_EXCEEDED 占位符）
        resumeParent(run, "BUDGET_EXCEEDED", hasReturnedText ? truncate(returned, MAX_PARTIAL_ANSWER_CHARS) : "AGENT_BUDGET_EXCEEDED");
        event(run,"RUN_BUDGET_EXCEEDED",json.createObjectNode().put("status","BUDGET_EXCEEDED")
                .put("partialAnswerSaved", hasReturnedText));
    }

    /** 部分产出正文的落库长度上限（模型可见/用户可见，不影响真实 usage 结算）。 */
    private static final int MAX_PARTIAL_ANSWER_CHARS = 20000;

    /**
     * 持久化已返回的部分回答文本：父运行（depth=0）写 ASSISTANT 消息；子运行（depth&gt;0）
     * 只回填自身 DELEGATION_COMPLETED 的 content 占位，不向共享会话写消息（R1 隔离）。
     * 与 {@link #recordBudgetPartialAnswer} 共用同一落库边界，但不改状态（状态由调用方推进）。
     */
    private void persistPartialAnswerText(AgentRunView run, String content) {
        if (content == null || content.isBlank()) return;
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(session_id,run_id,role,content)
                    VALUES (?,?,'ASSISTANT',?)
                    """, run.sessionId(), run.id(), content);
        } else if (run.parentRunId() != null) {
            // 子运行：把正文回填到 resumeParent 已写的错误码占位上（尚未写时后续 UPDATE 自然 no-op）
            jdbc.update("""
                    UPDATE agent_step SET output_json = jsonb_set(output_json, '{content}', ?::jsonb)
                    WHERE run_id=? AND type='DELEGATION_COMPLETED'
                      AND output_json->>'childRunId'=?
                      AND output_json->>'content'='AGENT_BUDGET_EXCEEDED'
                    """, jsonString(content), run.parentRunId(), run.id().toString());
        }
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
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?,?)
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()), errorCode);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='FAILED',steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,error_code=?,finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
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
        // retry_count 记录可重试模型失败次数（含最终失败），最多安排两次自动重试。
        // MODEL_RETRY 记录实际安排的重试次数；最终失败不再增加该诊断计数。
        boolean modelRetry=retryable && !"FORMAT_REPAIR_REQUESTED".equals(errorCode);
        // 可重试失败是否已到"不再安排自动重试"的边界：一是重试次数，二是本运行活跃时长。
        // 时长判据必须读取<b>本运行适用的同一有效策略</b>（v2 根 45 分钟 / 子 30 分钟，
        // v1 沿用原 Skill 额度），不能保留固定的 300000ms 隐藏截停——否则统一放宽到
        // 45 分钟后仍会在 5 分钟处被这一层判停（设计 3 节明确要求的冲突消除）。
        long activeBudgetMillis = com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits
                .forRun(run.contextPolicyVersion(), run.depth(), run.skillCode())
                .maxRunDuration().toMillis();
        boolean activeTimeExhausted = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active_elapsed_ms>=? FROM agent_run WHERE id=?", Boolean.class,
                activeBudgetMillis, run.id()));
        boolean finalFailure = !retryable || (modelRetry && run.retryCount() >= 2)
                || activeTimeExhausted;
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

    // ========== 主动暂停与继续（控制边界） ==========
    //
    // 暂停意图 ≠ 已暂停：RUNNING 上的意图只写 pause_requested_at（不递增 version，
    // 不使在途响应的落库 CAS 失配），由 worker 在动作边界经 recordPaused 确认；
    // 排队/重试等待中的运行没有在途 worker，原子直接转 PAUSED。
    // 恢复只做 PAUSED → QUEUED，不清零额度、重试次数与已完成记录。

    /**
     * 用户发起暂停。状态决定行为：
     * QUEUED / FAILED_RETRYABLE 原子转 PAUSED（同事务记录 RUN_PAUSED）；
     * RUNNING 只落暂停意图（同事务记录 RUN_PAUSE_REQUESTED），已带意图时幂等返回；
     * PAUSED 幂等返回；其余状态（终态、等待外部输入）明确业务错误。
     */
    @Transactional
    public AgentRunView requestPause(UUID projectId, UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT status, pause_requested_at IS NOT NULL AS requested
                FROM agent_run WHERE project_id=? AND id=? FOR UPDATE
                """, projectId, runId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        AgentRunStatus status = AgentRunStatus.valueOf((String) rows.getFirst().get("status"));
        boolean requested = Boolean.TRUE.equals(rows.getFirst().get("requested"));
        switch (status) {
            case PAUSED -> { /* 幂等：已暂停，无重复事件 */ }
            case QUEUED, FAILED_RETRYABLE -> {
                requireRunUpdate(jdbc.update("""
                        UPDATE agent_run SET status='PAUSED',
                          pause_requested_at=COALESCE(pause_requested_at,now()),
                          updated_at=now(),version=version+1
                        WHERE project_id=? AND id=? AND status=?
                        """, projectId, runId, status.name()));
                event(projectId, runId, "RUN_PAUSED",
                        json.createObjectNode().put("status", AgentRunStatus.PAUSED.name()));
            }
            case RUNNING -> {
                if (!requested) {
                    requireRunUpdate(jdbc.update("""
                            UPDATE agent_run SET pause_requested_at=now()
                            WHERE project_id=? AND id=? AND status='RUNNING' AND pause_requested_at IS NULL
                            """, projectId, runId));
                    event(projectId, runId, "RUN_PAUSE_REQUESTED",
                            json.createObjectNode().put("status", AgentRunStatus.RUNNING.name()));
                }
            }
            default -> throw new BusinessException(ErrorCode.AGENT_RUN_NOT_PAUSABLE);
        }
        return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
    }

    /**
     * 用户发起继续。仅 PAUSED → QUEUED（同事务记录 RUN_RESUMED，清除暂停意图）；
     * 已恢复到 QUEUED/RUNNING 且没有新的暂停意图时幂等返回当前运行，不二次推进；
     * 暂停尚未完成（RUNNING 带意图）不允许抢先恢复；终态不复活。
     */
    @Transactional
    public AgentRunView requestResume(UUID projectId, UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT status, pause_requested_at IS NOT NULL AS requested
                FROM agent_run WHERE project_id=? AND id=? FOR UPDATE
                """, projectId, runId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        AgentRunStatus status = AgentRunStatus.valueOf((String) rows.getFirst().get("status"));
        boolean requested = Boolean.TRUE.equals(rows.getFirst().get("requested"));
        if (status == AgentRunStatus.PAUSED) {
            requireRunUpdate(jdbc.update("""
                    UPDATE agent_run SET status='QUEUED',pause_requested_at=NULL,finished_at=NULL,
                      updated_at=now(),version=version+1
                    WHERE project_id=? AND id=? AND status='PAUSED'
                    """, projectId, runId));
            event(projectId, runId, "RUN_RESUMED",
                    json.createObjectNode().put("status", AgentRunStatus.QUEUED.name()));
            return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        }
        if ((status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING) && !requested) {
            return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        }
        throw new BusinessException(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
    }

    /**
     * worker 在动作边界确认暂停：结清本 claim 的已确认执行段（R1 语义）并释放租约，
     * 使旧 claim 失效；<b>不</b>调用 markBatchHandled——未消费的模型轮次与 PENDING
     * 工具调用全部保留，恢复后按原身份继续。已完成/已暂停的运行不被改写（幂等）。
     */
    @Transactional
    public AgentRunView recordPaused(UUID projectId, UUID runId) {
        AgentLeaseScope.verify(jdbc, projectId, runId, false);
        accountActiveTime(runId);
        int updated = jdbc.update("""
                UPDATE agent_run SET status='PAUSED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  pause_requested_at=COALESCE(pause_requested_at,now()),
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, projectId, runId);
        if (updated == 0) {
            AgentRunStatus current = findRun(projectId, runId)
                    .map(AgentRunView::status)
                    .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
            if (current == AgentRunStatus.PAUSED || current.terminal()) {
                // 完成与暂停竞争时完成先落库：运行保持其真实终态，不改成 PAUSED
                return findRun(projectId, runId).orElseThrow();
            }
            throw new IllegalStateException("Agent 运行已进入不可暂停状态: " + current);
        }
        event(projectId, runId, "RUN_PAUSED",
                json.createObjectNode().put("status", AgentRunStatus.PAUSED.name()));
        return findRun(projectId, runId).orElseThrow();
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
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?,'AGENT_INVALID_DECISION')
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        boolean retry = !run.correctionAttempted();
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,correction_attempted=true,
                  steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,
                  error_code='AGENT_INVALID_DECISION',
                  lease_owner=NULL,lease_expires_at=NULL,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, retry ? "QUEUED" : "FAILED",
                tokens(completion.promptTokens(), completion.content()),
                tokens(completion.completionTokens(), completion.content()),
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
                  token_usage_estimated,usage_basis,latency_ms)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,?,?,?,?,?)
                """, run.id(), sequence, jsonString(answer),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,?::jsonb,?::jsonb)
                """, run.sessionId(), run.id(), answer.answer(),
                answer.citations().toString(), answer.inferences().toString());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,model_provider=?,model_name=?,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
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
                  prompt_tokens,completion_tokens,token_usage_estimated,usage_basis,latency_ms)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?,?,?,?,?,?)
                """, run.id(), sequence, call.tool(), call.arguments().toString(),
                result.toString(), truncate(call.reason(), 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,
                  model_provider=?,model_name=?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
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
        // v1 兼容：从父剩余额度切出（旧 DELEGATE 决策协议路径）。
        // v2：子运行使用自身独立执行额度，累计输入/输出为 NULL（不限额），
        // 不出现"父剩余多少、子只能多少"的切分，也不因父累计 token 用得多而拒绝。
        boolean independentChildBudget = !run.enforcesCumulativeTokenLimits();
        int childSteps = independentChildBudget
                ? AgentResourcePolicy.V2_CHILD_MAX_STEPS
                : run.maxSteps() - run.stepsUsed() - 1;
        int childTools = independentChildBudget
                ? AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS
                : run.maxToolCalls() - run.toolCallsUsed();
        Integer childInputs = independentChildBudget
                ? null : run.maxInputTokens() - run.inputTokensUsed() - promptTokens;
        Integer childOutputs = independentChildBudget
                ? null : run.maxOutputTokens() - run.outputTokensUsed() - outputTokens;
        if (childSteps < 1 || (childInputs != null && childInputs < 1) || (childOutputs != null && childOutputs < 1)) {
            throw new IllegalStateException("Agent 没有可分配给子运行的剩余预算");
        }
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens,budget_semantics,context_policy_version)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?,(SELECT budget_semantics FROM agent_run WHERE id=?),
                  (SELECT context_policy_version FROM agent_run WHERE id=?))
                RETURNING *
                """, AgentRunMappers.runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), delegate.role(), delegate.objective(),
                childSteps, childTools, childInputs, childOutputs, run.id(), run.id());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='CREATED',children_used=children_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, promptTokens, outputTokens, promptTokens, outputTokens,
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
     * 批次因工具额度无法受理（新增调用超出剩余额度）：整批跳过（PENDING 调用 SKIPPED、
     * 轮次标记已消费，恢复不重放），并按<b>请求的批次规模</b>消耗工具额度（LEAST 封顶）——
     * 该批次的调用请求就是本次运行的额度终点，防止"拒绝→重新排队→再拒绝"空转；
     * 运行重新排队，下一次准入由收敛策略决定无工具总结（有可信证据且收尾可负担）
     * 或预算终止。落一条 ERROR 步骤记录拒绝原因（不占推进步），诊断可见。
     */
    @Transactional
    public void consumeToolBatchQuotaAndRequeue(AgentRunView run, int requestedCalls) {
        jdbc.update("""
                UPDATE agent_run SET tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?)
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, requestedCalls, run.projectId(), run.id());
        appendErrorStep(run, "TOOL_BUDGET_BATCH_REJECTED");
        requeueRun(run);
    }

    /**
     * 记录模型轮次。同时更新 Run 的 token 预算计数和版本。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn) {
        return recordModelTurn(run, turn, null);
    }

    /**
     * 记录模型轮次并持久化本轮的可信执行模式（NATIVE_TOOLS / LEGACY_READ_ONLY）。
     * source_mode 用于恢复待处理调用时按来源轮次校验写工具权限；历史数据可为空。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn, String sourceMode) {
        return recordModelTurn(run, turn, sourceMode, null);
    }

    /**
     * 带<b>调用身份</b>的记录版本：modelCallId 随 MODEL_COMPLETED 事件透出，
     * 前端据此把流式正文预览与持久化的完整轮次关联；null 保留历史行为（事件无该字段）。
     */
    @Transactional
    public AgentRunView recordModelTurn(
            AgentRunView run, ModelTurnResult turn, String sourceMode, String modelCallId) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        markProgress(run.id());
        int inputTokens = estimateInputTokens(turn);
        int outputTokens = tokens(turn.usage() == null ? null : turn.usage().outputTokens(), jsonString(turn));
        boolean estimated = turn.usage() == null || turn.usage().inputTokens() == null || turn.usage().outputTokens() == null;

        // 先验证版本和状态，避免版本冲突时留下孤立 Step；
        // used 封顶保持预算语义，actual 如实累计——真实输入可以超出 max_input_tokens
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  steps_used=steps_used+1,
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, inputTokens, outputTokens, inputTokens, outputTokens, estimated,
                run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,model_provider,model_name)
                VALUES (?,?,'MODEL_TURN',?,?,?,?,?,?,?,?)
                """, run.id(), sequence,
                truncate(turn.content(), 2000),
                inputTokens,
                turn.usage() != null ? turn.usage().outputTokens() : null,
                estimated,
                // 模型轮次必然有内容/工具调用证据：无上报时按证据估算，不标 UNKNOWN
                turn.usage() == null ? "ESTIMATED"
                        : usageBasis(turn.usage().inputTokens(), turn.usage().outputTokens()),
                boundedLatency(turn.latencyMs()),
                truncate(turn.provider(), 80),
                truncate(turn.model(), 120));
        jdbc.update("UPDATE agent_step SET output_json=?::jsonb WHERE run_id=? AND sequence_no=?", jsonString(turn), run.id(), sequence);
        for (int ordinal = 0; ordinal < turn.toolCalls().size(); ordinal++) {
            var call = turn.toolCalls().get(ordinal);
            UUID invocationId = UUID.nameUUIDFromBytes((run.id() + ":" + sequence + ":" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status,source_mode)
                    VALUES (?,?,?,?,?,?,?::jsonb,'PENDING',?)
                    """, run.id(), sequence, ordinal, invocationId, call.id(), call.name(), call.arguments().toString(), sourceMode);
        }

        // 轮次正文随事件透出（与 reason 列同一 2000 码点上限），前端按序渲染过渡说明；
        // provider/model 为本轮实际响应的模型身份，事件重放与刷新恢复共用。
        // modelCallId 把预览与完整结果关联；历史事件与恢复路径缺该字段时按旧规则处理
        var completedPayload = json.createObjectNode().put("stepSequence",sequence).put("finishReason",turn.finishReason().name()).put("toolCallCount",turn.toolCalls().size())
                .put("content",truncate(turn.content(),2000))
                .put("provider",truncate(turn.provider(),80))
                .put("model",truncate(turn.model(),120));
        if (modelCallId != null) completedPayload.put("modelCallId", modelCallId);
        event(run,"MODEL_COMPLETED",completedPayload);
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
        markProgress(run.id());
        // 先验证版本和状态，避免版本冲突时留下孤立 Step。
        // 工具结果只计工具额度；推进步按预算语义分支（COMBINED 旧语义逐项占用，SEPARATED 不占）
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
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
        return recordFinal(run, content, citations, false);
    }

    /**
     * 收口"上一 claim 已持久化、尚未消费"的模型文本轮次并记录最终答案：
     * 结果消费标记与终态、步骤、消息、事件在同一事务内完成。重复接管不会二次消费或重复记账。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations,
            boolean consumePersistedTurn) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
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

        // 使用 ObjectMapper 序列化为 JSON 对象，确保 JSONB 正确。
        // citations 参数是协调器移交的结构化来源（已完成委派子运行的持久引用投影），
        // 与本运行自己的持久证据合并——两者都来自持久化工具结果，不接受模型编造的 ID。
        var evidence = collectEvidence(run.id(), run.projectId());
        if (citations != null) {
            for (AgentCitation handed : citations) {
                if (handed != null) evidence.putIfAbsent(handed.chunkId(), handed);
            }
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
        // 子研究运行不向共享会话写 ASSISTANT 消息（R1 隔离）：其产出由父运行读取
        // DELEGATION_COMPLETED 后综合落一份最终回答；中间结果只持久化在子运行自身。
        boolean childRun = run.depth() > 0;
        if (!childRun) {
            jdbc.update("""
                    INSERT INTO agent_message(
                      session_id,run_id,role,content,citations_json,inferences_json)
                    VALUES (?,?,'ASSISTANT',?,?::jsonb,'[]'::jsonb)
                    """, run.sessionId(), run.id(), content, json.valueToTree(availableCitations).toString());
        }
        // 委派子运行成功收口：唤醒等待中的父运行（非父运行 no-op）
        resumeParent(run, "SUCCEEDED", content);
        event(run,"RUN_SUCCEEDED",json.createObjectNode().put("status","SUCCEEDED"));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录等待用户输入状态。
     */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question) {
        return recordWaitingForInput(run, question, false);
    }

    /** 等待输入 + 原子消费已持久化文本轮次（恢复路径专用，见 {@link #recordFinal(AgentRunView, String, List, boolean)}）。 */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question, boolean consumePersistedTurn) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
        // 子研究运行不推进主会话工作状态（R9 隔离）：question() 只面向用户会话的父运行
        if (run.depth() == 0) AgentWorkingState.question(jdbc,json,run.sessionId(),question);
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
        // 子研究运行不提问、不进主会话（R1/R9 隔离）：等待输入语义只属于面向用户的父运行
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(
                      session_id,run_id,role,content,citations_json,inferences_json)
                    VALUES (?,?,'ASSISTANT',?,'[]'::jsonb,'[]'::jsonb)
                    """, run.sessionId(), run.id(), question);
        } else {
            jdbc.update("UPDATE agent_session SET working_state=jsonb_set(working_state,'{pendingQuestion}','null'::jsonb,true) WHERE project_id=? AND id=?", run.projectId(), run.sessionId());
        }
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
        jdbc.update("UPDATE agent_run SET active_elapsed_ms=active_elapsed_ms+CASE WHEN claim_started_at IS NULL THEN 0 ELSE GREATEST(0,(extract(epoch FROM(now()-claim_started_at))*1000)::bigint) END,claim_started_at=NULL,last_progress_at=NULL WHERE id=?",runId);
    }

    /**
     * 确认一次执行进度：本 claim 的已执行时长上界推进到当前时刻。
     * 只在 worker 真实推进运行状态的落库事务内调用（模型轮次、工具结果、委派）；
     * 供过期接管按"最后一次确认进度"结算上一个 claim 的执行时长，
     * 不把等待租约/离线的空闲时间计成执行时间。
     */
    private void markProgress(UUID runId) {
        jdbc.update("UPDATE agent_run SET last_progress_at=now() WHERE id=?", runId);
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

    /** usage 来源：提供商上报 / 按证据估算 / 无任何证据（不得把未知冒充上报或精确值）。 */
    private static String usageBasis(Integer inputTokens, Integer outputTokens) {
        if (inputTokens != null && outputTokens != null) return "PROVIDER";
        if (inputTokens == null && outputTokens == null) return "UNKNOWN";
        return "ESTIMATED";
    }

    /**
     * 每次实际出站模型请求的持久化调用身份：请求发出前先落一行（usage_basis=UNKNOWN、
     * token 列为 NULL）。正常记账与取消补记共用同一身份一次性结算；
     * 相同内容再次真实请求提供商是新的出站请求，会得到新的身份、如实再次入账，
     * 不与同一次请求的重复结算共享去重身份。
     *
     * <p>本方法同时是模型请求的<b>持久化准入边界</b>：行锁与暂停意图/状态检查同事务，
     * 与 requestPause 的行锁串行化——暂停意图先落库则不再发起新请求（抛 AGENT_RUN_PAUSED，
     * 由协调器转入 PAUSED）；请求身份先落库则允许本次请求完成当前阶段。</p>
     */
    @Transactional
    public String beginModelCall(UUID projectId, UUID runId, String kind) {
        var admission = jdbc.queryForList(
                "SELECT status,pause_requested_at FROM agent_run WHERE id=? FOR UPDATE", runId);
        if (admission.isEmpty()) throw new IllegalStateException("Agent 运行不存在: " + runId);
        if (admission.getFirst().get("pause_requested_at") != null) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不再启动新的模型请求");
        }
        if (!"RUNNING".equals(admission.getFirst().get("status"))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED, "Agent 运行已离开运行状态");
        }
        return jdbc.queryForObject("""
                INSERT INTO agent_usage_settlement(run_id,call_id,kind,usage_basis)
                VALUES (?,gen_random_uuid(),?,'UNKNOWN')
                RETURNING call_id
                """, String.class, runId, kind);
    }

    /** 未结算行判定：身份行（UNKNOWN + token 列为 NULL）。结算后 token 列非 NULL，不再匹配。 */
    private static final String UNSETTLED_PREDICATE =
            " AND usage_basis='UNKNOWN' AND input_tokens_actual IS NULL AND output_tokens_actual IS NULL";

    /**
     * 正常记账（原子）：身份行首次结算与运行用量累计、步骤写入在同一事务内完成。
     * 先以条件更新独占结算身份行（仅未结算行可占用，占用失败即拒绝累计），
     * 再执行 recordModelTurn 的运行累计与步骤写入；任一步失败则整体回滚——
     * 同一身份无论以什么顺序、被调用多少次，总额只增加一次。模型网络请求不在本事务内。
     */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement) {
        return recordModelTurnWithSettlement(run, turn, callId, kind, settlement, null);
    }

    /** 带本轮可信执行模式的原子记账版本；source_mode 随工具调用清单持久化供恢复校验。 */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement, String sourceMode) {
        return recordModelTurnWithSettlement(run, turn, callId, kind, settlement, sourceMode, null);
    }

    /**
     * 原子记账并持久化本轮请求<b>实际采用</b>的强制收尾意图：{@code finalizingIntent} 必须来自
     * 消息组装与 {@code needsFinalRequest} 调整之后、真正出站请求使用的值，写进该轮次的持久化
     * 响应（与响应、用量结算同一事务），接管时据此按原请求语义处理已保存结果；
     * {@code null} 保留历史行为（该轮次不携带意图元数据）。
     */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement, String sourceMode,
            Boolean finalizingIntent) {
        claimIdentityOrThrow(run.id(), callId, kind, settlement);
        ModelTurnResult persisted = finalizingIntent == null ? turn : turn.withFinalizing(finalizingIntent);
        return recordModelTurn(run, persisted, sourceMode, callId);
    }

    /** 输出超限分支的原子版本：身份行结算与 recordBudgetExceeded 的运行累计同事务。 */
    @Transactional
    public void recordBudgetExceededWithSettlement(AgentRunView run, ChatCompletionResult completion,
            String callId, String kind, UsageSettlement settlement) {
        claimIdentityOrThrow(run.id(), callId, kind, settlement);
        recordBudgetExceeded(run, completion);
    }

    /**
     * 条件更新独占结算身份行：仅未结算行（UNKNOWN + token 列 NULL）可占用并写入结算值。
     */
    private int claimIdentity(UUID runId, String callId, String kind, UsageSettlement settlement) {
        return jdbc.update("""
                UPDATE agent_usage_settlement
                SET kind=?, input_tokens_actual=?, output_tokens_actual=?, usage_basis=?, latency_ms=?
                WHERE run_id=? AND call_id=?
                """ + UNSETTLED_PREDICATE,
                kind, settlement.bookedInput(), settlement.bookedOutput(), settlement.combinedBasis(),
                settlement.latencyMs() == null ? null : settlement.latencyMs().intValue(), runId, callId);
    }

    /** 占用身份行；占用失败（已结算或身份不存在）必须终止累计，由调用方在同一事务内回滚。 */
    private void claimIdentityOrThrow(UUID runId, String callId, String kind, UsageSettlement settlement) {
        if (claimIdentity(runId, callId, kind, settlement) == 1) return;
        boolean exists = jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, runId, callId) > 0;
        throw new IllegalStateException(exists
                ? "模型调用身份已结算，拒绝重复累计: " + callId
                : "模型调用身份不存在: " + callId);
    }

    /** 用量未确认判定：UNKNOWN 且两侧都无已确认数值（NULL 或 0）。被恢复收口或未知结算过的行
     *  仍可被迟到证据幂等补全；PROVIDER/ESTIMATED 属已确认结算，不再被覆盖。 */
    private static final String UNCONFIRMED_PREDICATE =
            " AND usage_basis='UNKNOWN' AND COALESCE(input_tokens_actual,0)=0 AND COALESCE(output_tokens_actual,0)=0";

    /** 恢复接管时收口未结算身份行：显式记为 0/0 + UNKNOWN（用量未确认），不虚构消耗；
     *  旧 worker 的迟到真实响应仍可按原身份补全（见 settleOrphanUsage），业务动作由租约拦截。 */
    @Transactional
    public int closeUnresolvedModelCalls(UUID runId) {
        return jdbc.update("""
                UPDATE agent_usage_settlement
                SET input_tokens_actual=0, output_tokens_actual=0, latency_ms=NULL
                WHERE run_id=?
                """ + UNSETTLED_PREDICATE, runId);
    }

    /**
     * 结算"状态机已离开 RUNNING"后仍须如实入账的模型用量：
     * 模型调用已发生但运行被取消/并发推进/业务状态校验失败时，used 封顶、actual 如实累计。
     * 结算基于 {@link #beginModelCall} 落库的调用身份，只有"用量未确认"的行会入账：
     * 重复回调、恢复与取消竞争只入账一次；恢复收口（0/0 UNKNOWN）或未知结算的行
     * 允许迟到证据幂等补全（此前入账为 0，补全后总额恰为真实值）；
     * PROVIDER/ESTIMATED 已确认结算的行拒绝覆盖。业务状态已终结不妨碍结算已发生费用。
     */
    @Transactional
    public boolean settleOrphanUsage(
            UUID projectId, UUID runId, String callId, String kind, UsageSettlement usage) {
        int settled = jdbc.update("""
                UPDATE agent_usage_settlement
                SET kind=?, input_tokens_actual=?, output_tokens_actual=?, usage_basis=?, latency_ms=?
                WHERE run_id=? AND call_id=?
                """ + UNCONFIRMED_PREDICATE,
                kind, usage.bookedInput(), usage.bookedOutput(), usage.combinedBasis(),
                usage.latencyMs() == null ? null : usage.latencyMs().intValue(), runId, callId);
        if (settled == 0) return false; // 已确认结算：不重复入账
        bookUsageToRun(projectId, runId, usage);
        return true;
    }

    private void bookUsageToRun(UUID projectId, UUID runId, UsageSettlement usage) {
        jdbc.update("""
                UPDATE agent_run SET
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now()
                WHERE project_id=? AND id=?
                """, usage.bookedInput(), usage.bookedOutput(),
                usage.bookedInput(), usage.bookedOutput(), usage.estimated(), projectId, runId);
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

    /**
     * 子运行终态回收：唤醒等待中的父运行，回收已发生的消耗并移交结构化结果。
     *
     * <p>消息隔离（R1）：子运行自身不向会话写 ASSISTANT 消息——它的产出只以
     * DELEGATION_COMPLETED step（本方法）持久化，由父运行综合后以父身份落一份最终回答。</p>
     *
     * <p>用量回收（R5）：已发生消耗的回收独立于能否唤醒父运行——父处于 RUNNING/PAUSED
     * 等任何状态时用量照样累加（幂等键 DELEGATION_COMPLETED step 防重复回收），
     * 只是把父运行唤醒为 QUEUED 的部分仍要求 CREATED/QUEUED；父保持 PAUSED 不被自动解除。</p>
     *
     * <p>来源身份（R7）：回收本运行成功工具结果中的有效 citations，随 DELEGATION_COMPLETED
     * 持久化，父运行综合时能把这些来源投影到自己的最终回答，不依赖模型重新编造引用 ID。</p>
     *
     * <p>覆盖事实：同时从子运行持久化工具结果推导最小结构化覆盖（文档/版本身份、提纲取得状态与
     * 可信度、已读章节、分页/截断限制、已知缺口、结束原因），由
     * {@link DelegatedResearchCoverage} 提取——不由模型填写、不追加统计用工具调用、
     * 不回传工具原文。父运行据此如实说明范围，不再只凭子运行的文字转述
     * （真实 B-narrow2 实验：父综合误称"未取得提纲"）。</p>
     */
    private void resumeParent(AgentRunView child, String status, String content) {
        if (child.parentRunId() == null) return;
        // 幂等：同一子运行只回收一次。恢复/重复收口重放时直接返回，不重复写 step、不重复累计用量。
        boolean alreadyCollected = jdbc.queryForObject("""
                SELECT count(*) FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED' AND output_json->>'childRunId'=?
                """, Integer.class, child.parentRunId(), child.id().toString()) > 0;
        if (alreadyCollected) return;
        AgentRunView usage = findRun(child.projectId(), child.id()).orElse(child);
        // 子研究运行的可验证来源身份：本运行成功工具结果中真实存在的 chunk/document 投影
        java.util.LinkedHashMap<UUID, AgentCitation> childCitations = collectEvidence(child.id(), child.projectId());
        int sequence = nextSequence(child.parentRunId());
        var completed = json.createObjectNode()
                .put("childRunId", child.id().toString())
                .put("status", status)
                .put("content", content);
        completed.set("citations", json.valueToTree(childCitations.values().stream().limit(50).toList()));
        completed.set("coverage", DelegatedResearchCoverage.extract(json, persistedToolResults(child.id()), status));
        completed.put("usage", json.createObjectNode()
                .put("inputTokensUsed", usage.inputTokensUsed())
                .put("outputTokensUsed", usage.outputTokensUsed())
                .put("inputTokensActual", usage.inputTokensActual())
                .put("outputTokensActual", usage.outputTokensActual()));
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'DELEGATION_COMPLETED',?::jsonb,?)
                """, child.parentRunId(), sequence, jsonString(completed),
                "Specialist child run completed");
        // 已发生消耗的回收独立于唤醒。v1 沿用"并入父运行共享额度"的既有语义；
        // v2 父子执行额度独立：子消耗只做<b>真实统计</b>（actual 与 used 如实累计一次），
        // <b>不扣减父自身的步骤/工具执行额度</b>——否则子研究会把父综合的额度吃掉
        // （设计要求：子消耗回收只用于真实统计，不扣减父自身步骤/工具额度）。
        AgentRunView parentView = findRun(child.projectId(), child.parentRunId()).orElse(null);
        boolean independentChildBudget = parentView != null && !parentView.enforcesCumulativeTokenLimits();
        if (independentChildBudget) {
            // v2：只累计 token 用量事实，不动父自身的 steps_used / tool_calls_used。
            // token 仍走 NULL 安全的封顶函数：v2 正常情况下 max_* 为 NULL（不封顶、只统计），
            // 但若该行仍带非 NULL 上限（兼容场景），必须继续保持 used <= max 的行约束，
            // 不能因放宽执行额度而写出违反 ck_agent_run_budgets 的行。
            jdbc.update("""
                    UPDATE agent_run SET
                      input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                      output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                      input_tokens_actual=input_tokens_actual+?,
                      output_tokens_actual=output_tokens_actual+?,
                      token_usage_estimated=token_usage_estimated OR ?,
                      updated_at=now()
                    WHERE id=?
                    """, usage.inputTokensUsed(), usage.outputTokensUsed(),
                    usage.inputTokensActual(), usage.outputTokensActual(),
                    usage.tokenUsageEstimated(), child.parentRunId());
        } else {
            jdbc.update("""
                    UPDATE agent_run SET
                      steps_used=LEAST(max_steps,steps_used+?),
                      tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?),
                      input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                      output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                      input_tokens_actual=input_tokens_actual+?,
                      output_tokens_actual=output_tokens_actual+?,
                      token_usage_estimated=token_usage_estimated OR ?,
                      updated_at=now()
                    WHERE id=?
                    """, usage.stepsUsed(), usage.toolCallsUsed(), usage.inputTokensUsed(),
                    usage.outputTokensUsed(), usage.inputTokensActual(), usage.outputTokensActual(),
                    usage.tokenUsageEstimated(), child.parentRunId());
        }
        // 唤醒部分单独执行：只有 CREATED/QUEUED 的等待父运行被转回 QUEUED（再次确认时
        // no-op 不报错）；RUNNING（竞争窗口）保持运行，PAUSED 不被自动解除暂停。
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE id=? AND status IN ('CREATED','QUEUED')
                """, child.parentRunId());
    }

    /**
     * 子运行持久化的全部工具结果（成功与失败），按执行顺序返回给覆盖提取器：
     * tool_name / reason（TOOL_SUCCESS|TOOL_ERROR）/ input_json / output_json。
     * 只读取持久化事实，不调用工具、不让模型参与。
     */
    private List<DelegatedResearchCoverage.PersistedToolResult> persistedToolResults(UUID runId) {
        return jdbc.query("""
                SELECT tool_name, reason, input_json::text AS input_json, output_json::text AS output_json
                FROM agent_step
                WHERE run_id=? AND type='TOOL_CALL_COMPLETED' AND tool_name IS NOT NULL
                ORDER BY sequence_no
                """, (rs, index) -> new DelegatedResearchCoverage.PersistedToolResult(
                        rs.getString("tool_name"),
                        rs.getString("reason"),
                        parseJsonOrNull(rs.getString("input_json")),
                        parseJsonOrNull(rs.getString("output_json"))),
                runId);
    }

    private JsonNode parseJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException malformed) {
            // 结构损坏的持久化结果不冒充覆盖事实，按缺失处理
            return null;
        }
    }

    /** 本运行成功工具结果中的有效来源身份投影（{@link #recordFinal} 的证据查询共用逻辑）。 */
    private java.util.LinkedHashMap<UUID, AgentCitation> collectEvidence(UUID runId, UUID projectId) {
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
                """, String.class, runId, projectId, projectId)) {
            try { var citation = json.readValue(value, AgentCitation.class); evidence.putIfAbsent(citation.chunkId(), citation); }
            catch (JsonProcessingException malformed) { /* Only persisted, structured source identities are exposed. */ }
        }
        return evidence;
    }

    private static int estimateInputTokens(ModelTurnResult turn) {
        if (turn.usage() != null && turn.usage().inputTokens() != null) {
            return turn.usage().inputTokens();
        }
        return com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(Math.max(1,(turn.content()==null ? 0 : turn.content().length())/3));
    }

    // ========== document_research 只读委派（V62） ==========

    /**
     * 受理一次只读文档研究委派：创建 depth=1 的子运行、落 DELEGATION_REQUESTED step、
     * 委派工具结果落库并把父运行转回 QUEUED——全部在同一事务内完成。
     * 与旧 {@link #recordDelegation} 的差异：本方法不绑定一次模型轮次 completion
     * （委派发生在工具执行阶段），预算从父运行剩余额度切出。
     *
     * <p>幂等：同一 invocation（run + invocationId）重复执行返回既有结果，
     * 不再切预算、不再创建第二个子运行——崩溃恢复与重复接管安全。</p>
     *
     * <p>暂停/取消：父运行暂停意图先落库时拒绝受理（AGENT_RUN_PAUSED），由调用方按控制结果处理。</p>
     */
    @Transactional
    public JsonNode documentResearchDelegationResult(
            AgentRunView run, String invocationId, String objective) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        if (run.depth() != 0) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_ALLOWED, "子运行不能再委派（最多一层）");
        }
        // 幂等：同调用身份已有委派结果直接返回（恢复路径的重复执行）；
        // 或该运行已有同一 objective 的待决委派（父运行已转 QUEUED）时返回既有子运行
        var existing = jdbc.query("""
                SELECT i.result_json::text FROM agent_tool_invocation i
                WHERE i.run_id=? AND i.invocation_id::text=? AND i.status='SUCCEEDED'
                """, (rs, row) -> rs.getString(1), run.id(), invocationId).stream().findFirst();
        if (existing.isEmpty()) {
            existing = jdbc.query("""
                    SELECT jsonb_build_object('status','DELEGATED','childRunId',c.id::text)::text
                    FROM agent_step s
                    JOIN agent_run c ON c.parent_run_id=s.run_id AND c.role='KNOWLEDGE_RESEARCHER'
                    WHERE s.run_id=? AND s.type='DELEGATION_REQUESTED'
                      AND s.input_json->>'invocationId'=?
                    """, (rs, row) -> rs.getString(1), run.id(), invocationId).stream().findFirst();
        }
        if (existing.isPresent()) {
            try { return json.readTree(existing.get()); }
            catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pause_requested_at IS NOT NULL FROM agent_run WHERE id=?", Boolean.class, run.id()))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不启动委派");
        }
        if (run.childrenUsed() >= run.maxChildren()) {
            throw new com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException(
                    com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission
                            .RejectionReason.CHILDREN_EXHAUSTED.code(),
                    com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission
                            .RejectionReason.CHILDREN_EXHAUSTED.message());
        }

        // 子运行预算：v1 从父运行剩余额度切出（硬上限保证委派不放大总资源）；
        // v2 使用子运行自己的独立有限执行额度（不从父剩余切分，累计 token 不设上限），
        // 父仍须能发起委派并完成自身综合收尾——这一检查不能变成"父还剩几次工具，子只能读几页"。
        String parentSemantics = jdbc.queryForObject(
                "SELECT budget_semantics FROM agent_run WHERE id=?", String.class, run.id());
        boolean combined = "COMBINED".equals(parentSemantics);
        var facts = new com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.Facts(
                run.maxSteps(), run.stepsUsed(), run.maxToolCalls(), run.toolCallsUsed(),
                run.maxInputTokens(), run.inputTokensUsed(), run.maxOutputTokens(), run.outputTokensUsed(),
                run.childrenUsed(), run.maxChildren(), combined, run.contextPolicyVersion());
        // 剩余执行额度连"发起委派 + 父综合收尾"都容纳不了：明确拒绝受理
        // （可预期拒绝类型的异常，不先启动再注定失败），由调用方有边界地保留父运行、
        // 不把它判成失败。
        var rejection = com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.reject(facts);
        if (rejection != null) {
            throw new com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException(
                    rejection.code(), rejection.message());
        }
        var childBudget = com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.split(facts);
        int childSteps = childBudget.steps();
        int childToolCalls = childBudget.toolCalls();
        Integer childInput = childBudget.inputTokens();
        Integer childOutput = childBudget.outputTokens();

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,input_json,reason)
                VALUES (?,?,'DELEGATION_REQUESTED',?::jsonb,'DOCUMENT_RESEARCH_DELEGATED')
                """, run.id(), sequence,
                jsonString(Map.of("invocationId", invocationId, "role", "KNOWLEDGE_RESEARCHER",
                        "objective", truncate(objective, 3800))));

        UUID childId = UUID.randomUUID();
        // 子运行继承父运行的预算语义（V63）与资源策略版本（V64）：v2 父下子运行同样是
        // v2，累计输入/输出写 NULL（只统计、不限额），执行额度用自己的独立上界。
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens,budget_semantics,context_policy_version)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?,(SELECT budget_semantics FROM agent_run WHERE id=?),
                  (SELECT context_policy_version FROM agent_run WHERE id=?))
                RETURNING *
                """, AgentRunMappers.runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), "KNOWLEDGE_RESEARCHER", truncate(objective, 3800),
                childSteps, childToolCalls, childInput, childOutput, run.id(), run.id());

        ObjectNode result = json.createObjectNode();
        result.put("status", "DELEGATED");
        result.put("childRunId", child.id().toString());
        result.put("message", "研究任务已受理。子 Agent 完成后本运行自动继续，届时综合其发现回答用户；本轮不要重复委派。");

        // 父运行：登记 children_used 与委派自身的工具消耗（委派是一次真实的工具
        // 受理，与普通 recordToolResult 同一配额语义，不出现免费调用；推进步按
        // 预算语义分支），转回 QUEUED 等待子运行终态唤醒；不预扣子运行将要消耗的
        // 额度（子消耗在回收时并入）。
        // 委派工具结果在执行层身份行（invocation）上落 SUCCEEDED，恢复路径据此幂等复用。
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',children_used=children_used+1,
                  tool_calls_used=LEAST(max_tool_calls,tool_calls_used+1),
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
        jdbc.update("""
                UPDATE agent_tool_invocation SET status='SUCCEEDED',
                  result_json=?::jsonb,updated_at=now()
                WHERE run_id=? AND invocation_id=? AND status='PENDING'
                """, jsonString(result), run.id(), UUID.fromString(invocationId));
        // 委派批次全部完成：标记本运行最近模型轮次已消费，恢复路径不再重放委派调用
        markBatchHandled(run.id());
        return result;
    }

    /** 读取子运行的最终回答与状态（父运行综合时的证据来源；只读）。 */
    public java.util.Optional<AgentRunView> findRunById(UUID projectId, UUID runId) {
        return findRun(projectId, runId);
    }
}
